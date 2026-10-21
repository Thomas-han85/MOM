package com.handong.meetnote;

import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 폰 안에서 「누가 언제 말했나」를 가르고(분할), 구간마다 목소리 성문(256개 숫자)을 뽑는다.
 *
 * 왜 네이티브인가 (2026-10-21, 실장님 결정)
 *   OpenAI 화자 분리 모델은 목소리 본보기를 요청마다 넷까지만 받고 2027-02-26 에 닫힌다.
 *   실장님 목표는 연말까지 직원 20~30명의 목소리를 담아 두고 회의마다 누가 말했는지 가르는 것.
 *   그러려면 비교(성문)가 우리 손에 있어야 한다. 엔진은 sherpa-onnx(Apache 2.0),
 *   분할은 pyannote segmentation-3.0(MIT, APK 에셋), 성문은 3D-Speaker eres2net_base(Apache 2.0, 첫 사용 때 내려받음).
 *   9/3 자금회의 51분으로 실장님이 귀로 확인한 정답 291구간에서 eres2net_base 가 96% 를 맞췄다.
 *
 * 묶기(누가 같은 사람인가)는 여기서 하지 않는다. 화면 쪽(app.html)이 성문으로 묶고, 등록된 본보기와 맞춘다.
 * 그래야 문턱을 바꾸거나 본보기를 더해도 APK 를 다시 깔지 않는다. 여기서는 소리 → 구간 + 성문만.
 *
 * 메모리. 60분은 16kHz 로 5,760만 샘플 = 230MB float 다. 통째로 들면 폰이 앱을 죽인다(87분 회의에서 실제로 꺼진 적 있다).
 * 그래서 10분씩 잘라 돈다. 조각 경계에 걸린 말은 둘로 갈리지만, 묶기는 성문으로 다시 하므로 상관없다.
 *
 * JS 쪽 사용법:
 *   const { Diar } = Capacitor.Plugins;
 *   await Diar.status()                         // { seg:true, emb:false, embBytes }
 *   await Diar.prepare()                        // 성문 모델 내려받기. 'dl' 이벤트 {got,total}
 *   await Diar.begin(); await Diar.feed({data:b64}); ... await Diar.end();   // 16kHz 모노 PCM16 WAV 를 조각으로 넘김
 *   await Diar.run({ minSec:2, threads:4 })     // 'progress' 이벤트 {stage:"seg"|"emb", done, total}
 *      → { seconds, elapsedMs, dim, segments:[[start,end,localSpk],…], emb:[[segIndex,[…256]],…] }
 *   await Diar.cancel()
 */
@CapacitorPlugin(name = "Diar")
public class DiarPlugin extends Plugin {

    static final String SEG_ASSET = "pyannote-seg3.onnx";
    static final String EMB_NAME  = "3dspeaker_eres2net_base.onnx";
    static final long   EMB_SIZE  = 39593761L;   // 받은 파일이 이 크기가 아니면 깨진 것이다
    static final String EMB_URL   =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"
        + "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx";
    static final int RATE = 16000;
    static final int PIECE_SEC = 600;            // 10분씩

    private OutputStream feedOut = null;
    private File feedFile = null;
    private volatile boolean cancelled = false;
    private volatile Thread worker = null;

    /* ---------- 모델 파일 ---------- */

    private File modelDir() {
        File d = new File(getContext().getFilesDir(), "diar-models");
        if (!d.exists()) d.mkdirs();
        return d;
    }
    private File embFile() { return new File(modelDir(), EMB_NAME); }
    private boolean embReady() { File f = embFile(); return f.exists() && f.length() == EMB_SIZE; }

    /** 분할 모델은 APK 에 실려 온다. sherpa 는 파일 경로를 받으므로 한 번 꺼내 둔다. */
    private File segFile() throws Exception {
        File f = new File(modelDir(), SEG_ASSET);
        if (f.exists() && f.length() > 1000000) return f;
        try (InputStream in = getContext().getAssets().open(SEG_ASSET);
             OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return f;
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        boolean seg = false;
        try { seg = segFile().exists(); } catch (Exception e) { r.put("segError", String.valueOf(e.getMessage())); }
        r.put("seg", seg);
        r.put("emb", embReady());
        r.put("embBytes", embFile().exists() ? embFile().length() : 0);
        r.put("embTotal", EMB_SIZE);
        r.put("busy", worker != null);
        call.resolve(r);
    }

    /** 성문 모델을 내려받는다. 40MB, 한 번만. 받는 동안 'dl' 이벤트로 진행을 알린다. */
    @PluginMethod
    public void prepare(PluginCall call) {
        if (embReady()) { JSObject r = new JSObject(); r.put("ready", true); call.resolve(r); return; }
        new Thread(() -> {
            File tmp = new File(modelDir(), EMB_NAME + ".part");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(EMB_URL).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000); c.setReadTimeout(60000);
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                long total = c.getContentLengthLong();
                long got = 0, lastTell = 0;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16]; int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); got += n;
                        if (got - lastTell > 1048576) {
                            lastTell = got;
                            JSObject ev = new JSObject(); ev.put("got", got); ev.put("total", total > 0 ? total : EMB_SIZE);
                            notifyListeners("dl", ev);
                        }
                    }
                }
                if (tmp.length() != EMB_SIZE) throw new Exception("받은 크기가 다르다 " + tmp.length() + " ≠ " + EMB_SIZE);
                if (embFile().exists()) embFile().delete();
                if (!tmp.renameTo(embFile())) throw new Exception("파일을 옮기지 못했다");
                JSObject r = new JSObject(); r.put("ready", true); r.put("bytes", EMB_SIZE);
                call.resolve(r);
            } catch (Exception e) {
                try { tmp.delete(); } catch (Exception ignored) {}
                call.reject("성문 모델을 내려받지 못했습니다 · " + e.getMessage());
            }
        }, "diar-dl").start();
    }

    /* ---------- 소리 넘겨받기 (Saver 와 같은 꼴: 조각을 이어 한 파일로) ---------- */

    @PluginMethod
    public void begin(PluginCall call) {
        try {
            closeFeed();
            feedFile = new File(getContext().getCacheDir(), "diar-in.wav");
            if (feedFile.exists()) feedFile.delete();
            feedOut = new FileOutputStream(feedFile);
            call.resolve();
        } catch (Exception e) { call.reject("소리 파일을 열지 못했습니다 · " + e.getMessage()); }
    }

    @PluginMethod
    public void feed(PluginCall call) {
        try {
            if (feedOut == null) throw new Exception("begin 을 먼저 불러야 합니다");
            byte[] b = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            feedOut.write(b);
            JSObject r = new JSObject(); r.put("bytes", feedFile.length() + 0L);
            call.resolve(r);
        } catch (Exception e) { call.reject("소리 조각을 쓰지 못했습니다 · " + e.getMessage()); }
    }

    @PluginMethod
    public void end(PluginCall call) {
        try {
            closeFeed();
            JSObject r = new JSObject(); r.put("bytes", feedFile == null ? 0 : feedFile.length());
            call.resolve(r);
        } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    private void closeFeed() {
        if (feedOut != null) {
            try { feedOut.flush(); } catch (Exception ignored) {}
            try { feedOut.close(); } catch (Exception ignored) {}
        }
        feedOut = null;
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelled = true;
        call.resolve();
    }

    /* ---------- 돌리기 ---------- */

    /** WAV 머리에서 소리가 시작하는 자리. 머리 길이를 44 로 못 박지 않는다 — 만든 쪽마다 다르다. */
    private static long wavDataAt(RandomAccessFile raf) throws Exception {
        raf.seek(0);
        byte[] h = new byte[12]; raf.readFully(h);
        if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F') return 0;   // 머리 없는 날 PCM
        long pos = 12, len = raf.length();
        byte[] ck = new byte[8];
        while (pos + 8 <= len) {
            raf.seek(pos); raf.readFully(ck);
            long size = (ck[4] & 0xffL) | ((ck[5] & 0xffL) << 8) | ((ck[6] & 0xffL) << 16) | ((ck[7] & 0xffL) << 24);
            if (ck[0] == 'd' && ck[1] == 'a' && ck[2] == 't' && ck[3] == 'a') return pos + 8;
            pos += 8 + size + (size & 1);
        }
        return 44;
    }

    @PluginMethod
    public void run(PluginCall call) {
        if (worker != null) { call.reject("이미 돌고 있습니다"); return; }
        if (!embReady()) { call.reject("성문 모델이 없습니다 · prepare 를 먼저"); return; }
        final double minSec = call.getDouble("minSec", 2.0);
        final int threads = Math.max(1, Math.min(8, call.getInt("threads", 4)));
        final File in = feedFile;
        if (in == null || !in.exists()) { call.reject("넘겨받은 소리가 없습니다"); return; }
        cancelled = false;
        call.setKeepAlive(true);
        worker = new Thread(() -> {
            OfflineSpeakerDiarization sd = null;
            SpeakerEmbeddingExtractor ex = null;
            long t0 = System.currentTimeMillis();
            try {
                String segPath = segFile().getAbsolutePath();
                String embPath = embFile().getAbsolutePath();
                /* 코틀린 data class 는 자바에서 인자를 다 넘겨야 한다(기본값은 코틀린에서만 산다). */
                OfflineSpeakerSegmentationPyannoteModelConfig py =
                    new OfflineSpeakerSegmentationPyannoteModelConfig(segPath, 0.1f);
                OfflineSpeakerSegmentationModelConfig segCfg =
                    new OfflineSpeakerSegmentationModelConfig(py, threads, false, "cpu");
                SpeakerEmbeddingExtractorConfig embCfg =
                    new SpeakerEmbeddingExtractorConfig(embPath, threads, false, "cpu");
                /* 묶기는 화면 쪽이 다시 하므로 여기 문턱은 아무 값. numClusters -1 = 알아서. */
                FastClusteringConfig clu = new FastClusteringConfig(-1, 0.5f, false);
                OfflineSpeakerDiarizationConfig cfg =
                    new OfflineSpeakerDiarizationConfig(segCfg, embCfg, clu, 0.3f, 0.5f);
                sd = new OfflineSpeakerDiarization(null, cfg);
                ex = new SpeakerEmbeddingExtractor(null, embCfg);

                RandomAccessFile raf = new RandomAccessFile(in, "r");
                long dataAt = wavDataAt(raf);
                long samplesTotal = (raf.length() - dataAt) / 2;
                int pieces = (int) ((samplesTotal + (long) PIECE_SEC * RATE - 1) / ((long) PIECE_SEC * RATE));
                if (pieces < 1) throw new Exception("소리가 비어 있습니다");

                JSArray segments = new JSArray();
                JSArray embs = new JSArray();
                int dim = 0;
                int segIndex = 0;
                byte[] bytes = new byte[PIECE_SEC * RATE * 2];
                for (int p = 0; p < pieces; p++) {
                    if (cancelled) throw new Exception("취소했습니다");
                    long off = dataAt + (long) p * PIECE_SEC * RATE * 2;
                    int want = (int) Math.min(bytes.length, raf.length() - off);
                    raf.seek(off); raf.readFully(bytes, 0, want);
                    int n = want / 2;
                    float[] x = new float[n];
                    for (int i = 0; i < n; i++) {
                        int v = (bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8);
                        x[i] = v / 32768f;
                    }
                    final int pieceNo = p;
                    final double base = (double) p * PIECE_SEC;
                    OfflineSpeakerDiarizationSegment[] segs = sd.processWithCallback(x, (done, total, arg) -> {
                        JSObject ev = new JSObject();
                        ev.put("stage", "seg"); ev.put("piece", pieceNo); ev.put("pieces", pieces);
                        ev.put("done", done); ev.put("total", total);
                        notifyListeners("progress", ev);
                        return cancelled ? 1 : 0;
                    }, 0L);
                    if (cancelled) throw new Exception("취소했습니다");

                    int k = 0;
                    for (OfflineSpeakerDiarizationSegment s : segs) {
                        double a = s.getStart(), b = s.getEnd();
                        JSArray row = new JSArray();
                        row.put(Math.round((base + a) * 100.0) / 100.0);
                        row.put(Math.round((base + b) * 100.0) / 100.0);
                        row.put(s.getSpeaker());
                        segments.put(row);
                        if (b - a >= minSec) {
                            int ia = Math.max(0, (int) (a * RATE)), ib = Math.min(n, (int) (b * RATE));
                            float[] slice = new float[ib - ia];
                            System.arraycopy(x, ia, slice, 0, ib - ia);
                            OnlineStream st = ex.createStream();
                            st.acceptWaveform(slice, RATE);
                            st.inputFinished();
                            float[] e = ex.compute(st);
                            st.release();
                            dim = e.length;
                            /* 단위 길이로 맞춰 보낸다 — 화면 쪽은 내적만 하면 코사인이 된다 */
                            double norm = 0; for (float f : e) norm += f * f; norm = Math.sqrt(norm) + 1e-9;
                            JSArray vec = new JSArray();
                            for (float f : e) vec.put(Math.round(f / norm * 10000.0) / 10000.0);
                            JSArray pair = new JSArray(); pair.put(segIndex); pair.put(vec);
                            embs.put(pair);
                        }
                        segIndex++;
                        if ((++k % 20) == 0) {
                            JSObject ev = new JSObject();
                            ev.put("stage", "emb"); ev.put("piece", pieceNo); ev.put("pieces", pieces);
                            ev.put("done", k); ev.put("total", segs.length);
                            notifyListeners("progress", ev);
                        }
                        if (cancelled) throw new Exception("취소했습니다");
                    }
                }
                raf.close();
                JSObject r = new JSObject();
                r.put("seconds", Math.round(samplesTotal / (double) RATE));
                r.put("elapsedMs", System.currentTimeMillis() - t0);
                r.put("dim", dim);
                r.put("segments", segments);
                r.put("emb", embs);
                call.resolve(r);
            } catch (Throwable e) {
                call.reject("화자 분리 실패 · " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                try { if (sd != null) sd.release(); } catch (Throwable ignored) {}
                try { if (ex != null) ex.release(); } catch (Throwable ignored) {}
                try { if (in.exists()) in.delete(); } catch (Throwable ignored) {}
                worker = null;
            }
        }, "diar-run");
        worker.start();
    }
}
