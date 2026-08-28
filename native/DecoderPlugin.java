package com.handong.meetnote;

import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.database.Cursor;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 소리 파일을 흘려가며 풀어 조각으로 떨군다.
 *
 * ── 왜 필요한가 ──
 * 브라우저의 decodeAudioData 는 파일을 통째로 펼친다. 32비트 실수라 1분에 약 4MB 다.
 * 87분이면 그것만 340MB 이고, 표본율까지 바꾸면 두 배가 된다. 실제로 그렇게 앱이
 * 꺼졌다. 그래서 웹 쪽에는 길이 제한을 둘 수밖에 없었고, 긴 회의는 사람이 손으로
 * 잘라 넣어야 했다. 그것이 이 플러그인을 만든 까닭이다.
 *
 * 여기서는 MediaExtractor 로 한 덩이씩 읽어 MediaCodec 으로 풀고, 16kHz 모노로 줄여
 * 곧바로 조각 파일로 떨군다. 메모리에는 한 번에 몇 KB 만 있다. 파일 길이에 제한이 없다.
 *
 * 조각을 떨구는 자리와 이름은 RecorderService 와 똑같다(cache/segments/seg_00000_<시각>.wav).
 * 그래야 웹 쪽이 이미 가진 회수 경로(pending/read/discard)를 그대로 쓴다.
 *
 * JS 쪽 사용법:
 *   const { Decoder } = Capacitor.Plugins;
 *   const { files } = await Decoder.pick();              // [{uri, name, size}]
 *   await Decoder.decode({ uris:[...], chunkMs:30000 }); // 진행은 decodeProgress 로 알린다
 */
@CapacitorPlugin(name = "Decoder")
public class DecoderPlugin extends Plugin {

    private static final int RATE = 16000;          // 우리가 쓰는 표본율
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancel = false;

    /* ---------------- 파일 고르기 ---------------- */

    @PluginMethod
    public void pick(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{ "audio/*", "video/*" });
        startActivityForResult(call, Intent.createChooser(i, "소리 파일 고르기"), "picked");
    }

    @ActivityCallback
    private void picked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSArray out = new JSArray();
        Intent data = result.getData();
        if (data != null) {
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++)
                    out.put(describe(data.getClipData().getItemAt(k).getUri()));
            } else if (data.getData() != null) {
                out.put(describe(data.getData()));
            }
        }
        JSObject res = new JSObject();
        res.put("files", out);
        call.resolve(res);
    }

    /** 고른 파일의 이름과 크기를 읽어 둔다. 화면에 보여 주고 이름 순으로 세우기 위해서다. */
    private JSObject describe(Uri uri) {
        JSObject o = new JSObject();
        o.put("uri", uri.toString());
        String name = uri.getLastPathSegment();
        long size = 0;
        try (Cursor c = getContext().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0) size = c.getLong(si);
            }
        } catch (Exception ignored) { }
        o.put("name", name == null ? "audio" : name);
        o.put("size", size);
        return o;
    }

    /* ---------------- 풀어서 조각으로 ---------------- */

    @PluginMethod
    public void cancel(PluginCall call) { cancel = true; call.resolve(); }

    @PluginMethod
    public void decode(PluginCall call) {
        JSArray arr = call.getArray("uris");
        final int chunkMs = call.getInt("chunkMs", 30000);
        if (arr == null || arr.length() == 0) { call.reject("고른 파일이 없다"); return; }
        final List<String> uris = new ArrayList<>();
        try { for (Object o : arr.toList()) uris.add(String.valueOf(o)); }
        catch (Exception e) { call.reject("파일 목록을 읽지 못했다"); return; }

        cancel = false;
        call.setKeepAlive(true);
        worker.submit(() -> {
            int seq = 0;
            long offsetMs = 0;
            try {
                File dir = new File(getContext().getCacheDir(), "segments");
                if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                for (int f = 0; f < uris.size() && !cancel; f++) {
                    long[] made = decodeOne(Uri.parse(uris.get(f)), dir, seq, offsetMs,
                                            chunkMs, f + 1, uris.size());
                    seq = (int) made[0];
                    offsetMs = made[1];
                }
                File dir2 = new File(getContext().getCacheDir(), "segments");
                File[] left = dir2.listFiles();
                JSObject res = new JSObject();
                res.put("chunks", seq);
                res.put("onDisk", left == null ? 0 : left.length);
                res.put("durationMs", offsetMs);
                res.put("cancelled", cancel);
                call.resolve(res);
            } catch (Exception e) {
                call.reject("소리를 풀지 못했다: " + e.getMessage());
            } finally {
                call.setKeepAlive(false);
            }
        });
    }

    private void progress(int file, int total, long ms, int chunks) {
        JSObject ev = new JSObject();
        ev.put("file", file); ev.put("files", total);
        ev.put("ms", ms); ev.put("chunks", chunks);
        notifyListeners("decodeProgress", ev);
    }

    /**
     * 파일 하나를 풀어 조각으로 떨군다.
     * 반환값 [다음 순번, 다음 시각오프셋].
     */
    private long[] decodeOne(Uri uri, File dir, int seq, long offsetMs,
                             int chunkMs, int fileNo, int fileCount) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(getContext(), uri, null);

        int track = -1; MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
        }
        if (track < 0) { ex.release(); throw new Exception("소리 트랙이 없다"); }
        ex.selectTrack(track);

        /* 표본율과 채널은 코덱이 실제로 내놓는 형식이 참이다. 들어오는 형식과 다를 수 있다.
           아래 INFO_OUTPUT_FORMAT_CHANGED 에서 다시 읽어 덮어쓴다. */
        int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : RATE;
        int srcCh = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

        MediaCodec dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        dec.configure(fmt, null, null, 0);
        dec.start();

        /* 조각을 자를 규칙은 녹음기와 같게 둔다 —
           최소 길이를 채운 뒤 조용해지면 끊고, 최대 길이를 넘으면 강제로 끊는다.
           시계로만 자르면 단어 한가운데가 잘린다. */
        final int minSamples = (int) (RATE * Math.max(5000, chunkMs / 2L) / 1000L);
        final int maxSamples = (int) (RATE * Math.max(15000, chunkMs * 3L / 2L) / 1000L);
        final long silenceHoldMs = 700;

        RandomAccessFile raf = null; File cur = null;
        int curSamples = 0; long silentMs = 0; double noiseFloor = 900.0;
        double carry = 0;                    // 표본율을 줄일 때 남는 소수 자리
        short prev = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEnd = false, done = false;
        byte[] out = new byte[8192];
        int outLen = 0;

        try {
            while (!done && !cancel) {
                if (!sawInputEnd) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        /* 버퍼를 못 얻는 것과 소리가 끝난 것은 전혀 다른 일이다.
                           처음에는 둘을 같이 묶어 END_OF_STREAM 을 보냈다. 그러면 버퍼를
                           한 번만 못 얻어도 87분짜리가 1분에서 끊긴다. 실제로 그랬다. */
                        if (ib == null) {
                            dec.queueInputBuffer(ii, 0, 0, 0, 0);   // 빈 채로 돌려주고 다시 시도
                        } else {
                            int n = ex.readSampleData(ib, 0);
                            if (n < 0) {
                                dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                sawInputEnd = true;
                            } else {
                                dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    continue;
                }
                if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        // PCM 은 낮은 자리가 먼저다. ByteBuffer 는 기본이 그 반대라 못 박아야 한다.
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        // 16비트 PCM 으로 온다. 채널을 섞고 표본율을 16kHz 로 줄인다.
                        int frames = info.size / 2 / srcCh;
                        double step = (double) srcRate / RATE;
                        for (int i = 0; i < frames; i++) {
                            int acc = 0;
                            for (int c = 0; c < srcCh; c++) acc += ob.getShort();
                            short v = (short) (acc / srcCh);
                            carry += 1.0;
                            while (carry >= step) {
                                carry -= step;
                                if (raf == null) {
                                    cur = new File(dir, "seg_" + String.format(java.util.Locale.US, "%05d", seq)
                                            + "_" + (System.currentTimeMillis() + offsetMs) + ".wav");
                                    raf = new RandomAccessFile(cur, "rw");
                                    raf.setLength(0);
                                    raf.write(new byte[44]);
                                    curSamples = 0; silentMs = 0;
                                }
                                out[outLen++] = (byte) (v & 0xff);
                                out[outLen++] = (byte) ((v >> 8) & 0xff);
                                curSamples++;
                                if (outLen >= out.length) { raf.write(out, 0, outLen); outLen = 0; }

                                // 소리 크기를 눈대중으로 따라간다 (녹음기와 같은 방식)
                                double a = Math.abs(v);
                                if (a < noiseFloor) noiseFloor = noiseFloor * 0.90 + a * 0.10;
                                else                noiseFloor = noiseFloor * 0.995 + a * 0.005;
                                boolean quiet = a < Math.max(220.0, noiseFloor * 2.2);
                                silentMs = quiet ? silentMs + (1000L / RATE) : 0;

                                boolean end = curSamples >= minSamples && silentMs >= silenceHoldMs;
                                if (end || curSamples >= maxSamples) {
                                    if (outLen > 0) { raf.write(out, 0, outLen); outLen = 0; }
                                    closeWav(raf, curSamples);
                                    raf = null;
                                    offsetMs += curSamples * 1000L / RATE;
                                    seq++;
                                    if (seq % 10 == 0) progress(fileNo, fileCount, offsetMs, seq);
                                }
                            }
                            prev = v;
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                }
            }
        } finally {
            if (raf != null) {
                try { if (outLen > 0) raf.write(out, 0, outLen); } catch (Exception ignored) { }
                closeWav(raf, curSamples);
                offsetMs += curSamples * 1000L / RATE;
                seq++;
            }
            try { dec.stop(); } catch (Exception ignored) { }
            try { dec.release(); } catch (Exception ignored) { }
            try { ex.release(); } catch (Exception ignored) { }
        }
        progress(fileNo, fileCount, offsetMs, seq);
        return new long[]{ seq, offsetMs };
    }

    /** WAV 머리를 채우고 닫는다. 너무 짧은 조각은 버린다. */
    private void closeWav(RandomAccessFile raf, int samples) {
        try {
            if (samples < RATE / 5) { raf.close(); return; }   // 0.2초 미만은 버린다
            int dataLen = samples * 2;
            byte[] h = new byte[44];
            wstr(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); wstr(h, 8, "WAVE"); wstr(h, 12, "fmt ");
            le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1);
            le32(h, 24, RATE); le32(h, 28, RATE * 2); le16(h, 32, 2); le16(h, 34, 16);
            wstr(h, 36, "data"); le32(h, 40, dataLen);
            raf.seek(0); raf.write(h); raf.close();
        } catch (Exception ignored) { }
    }

    private void wstr(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }
    private void le16(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xff); b[o + 1] = (byte) ((v >> 8) & 0xff);
    }
    private void le32(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xff); b[o + 1] = (byte) ((v >> 8) & 0xff);
        b[o + 2] = (byte) ((v >> 16) & 0xff); b[o + 3] = (byte) ((v >> 24) & 0xff);
    }

    @Override
    protected void handleOnDestroy() {
        cancel = true;
        worker.shutdownNow();
        super.handleOnDestroy();
    }
}
