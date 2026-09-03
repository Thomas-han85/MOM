package com.handong.meetnote;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 기기에 내장된 안드로이드 음성으로 읽는다.
 *
 * 왜 필요한가: 앱 안에 들어가는 WebView 에는 웹 음성 기능(speechSynthesis)이 없다.
 * 크롬 브라우저에는 있지만 WebView 에는 빠져 있어서, 웹 방식으로는 앱에서 소리가 나지 않는다.
 * OpenAI 음성을 받아 재생하는 방법도 있으나 회의마다 돈이 들고 소리가 더 늦게 나온다.
 * 기기 음성은 공짜고 오프라인에서도 돌며 지연이 없다.
 *
 * JS 쪽 사용법:
 *   const { Tts } = Capacitor.Plugins;
 *   await Tts.available();                                  // { ready, pan, langs:{ko,vi,en} }
 *   await Tts.speak({ text:"...", lang:"ko-KR", rate:1.0, pan:-1 });
 *       // 큐에 쌓아 순서대로 읽는다. pan 은 -1 왼쪽 / 0 가운데 / +1 오른쪽
 *   await Tts.stop();
 *
 * 좌우 나눠 보내기에 대하여
 * ─────────────────────────
 * 처음에는 안드로이드 표준값인 KEY_PARAM_PAN 을 speak() 에 넘겼다. 문서상 맞는 방법이고
 * 코드도 짧았는데, 실기기에서 양쪽 귀로 다 나왔다. 엔진이 이 값을 지킬 의무가 없다 —
 * 조용히 무시하고 아무 오류도 내지 않는다. 그래서 엔진에 맡기는 것을 그만두었다.
 *
 * 지금은 이렇게 한다. synthesizeToFile() 로 음성을 WAV 로 받아, 한쪽 채널을 0 으로 채운
 * 스테레오 PCM 으로 엮어 AudioTrack 으로 직접 재생한다. 소리 자체가 한쪽에만 들어 있으므로
 * 엔진이 무엇을 하든 갈린다. setStereoVolume 같은 음량 조절도 쓰지 않는다 — 그것도
 * 기기에 따라 먹지 않는 일이 있다.
 *
 * pan 이 0 이면 예전 경로(speak) 를 그대로 쓴다. 굳이 파일을 거칠 까닭이 없고,
 * 가장 많이 쓰는 길을 건드리지 않는 편이 안전하다.
 */
@CapacitorPlugin(name = "Tts")
public class TtsPlugin extends Plugin {

    private TextToSpeech tts;
    private volatile boolean ready = false;
    private int counter = 0;

    /** 좌우로 나눠 보낼 것들. 합성이 끝나면 순서대로 하나씩 재생한다. */
    private final ExecutorService panPlayer = Executors.newSingleThreadExecutor();
    private final Map<String, PanJob> panJobs = new ConcurrentHashMap<>();
    private volatile AudioTrack panTrack = null;
    private volatile boolean panCancel = false;
    /** 마지막으로 좌우 나눠 재생한 것이 실제로 어땠는지. 진단에 쓴다. */
    private volatile String lastPan = "아직 없음";

    private static class PanJob {
        final File file; final boolean left;
        PanJob(File f, boolean l) { file = f; left = l; }
    }

    @Override
    public void load() {
        tts = new TextToSpeech(getContext(), status -> {
            ready = (status == TextToSpeech.SUCCESS);
            if (ready) {
                tts.setLanguage(Locale.KOREAN);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) {
                        PanJob job = panJobs.remove(id);
                        if (job != null) { panPlayer.submit(() -> playPanned(job)); return; }
                        emit(id, false);
                    }
                    @Override public void onError(String id) {
                        PanJob job = panJobs.remove(id);
                        if (job != null) { if (job.file != null) job.file.delete(); }
                        emit(id, true);
                    }
                });
            }
            JSObject ev = new JSObject();
            ev.put("ready", ready);
            notifyListeners("ttsReady", ev);
        });
    }

    private void emit(String id, boolean failed) {
        JSObject ev = new JSObject();
        ev.put("id", id);
        ev.put("error", failed);
        notifyListeners("ttsDone", ev);
    }

    /** "ko-KR" 같은 문자열을 Locale 로. 베트남어와 영어까지 다룬다. */
    private Locale toLocale(String lang) {
        if (lang == null) return Locale.KOREAN;
        String l = lang.toLowerCase();
        if (l.startsWith("vi")) return new Locale("vi", "VN");
        if (l.startsWith("en")) return Locale.US;
        return Locale.KOREAN;
    }

    /** 해당 언어 음성이 기기에 실제로 깔려 있는지. */
    private boolean supports(Locale loc) {
        if (!ready) return false;
        int r = tts.isLanguageAvailable(loc);
        return r == TextToSpeech.LANG_AVAILABLE
            || r == TextToSpeech.LANG_COUNTRY_AVAILABLE
            || r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE;
    }

    @PluginMethod
    public void available(PluginCall call) {
        JSObject langs = new JSObject();
        langs.put("ko", supports(Locale.KOREAN));
        langs.put("vi", supports(new Locale("vi", "VN")));
        langs.put("en", supports(Locale.US));
        JSObject res = new JSObject();
        res.put("ready", ready);
        res.put("langs", langs);
        // 좌우 나눠 보내기를 이 APK 가 아는지. 웹 쪽이 이걸 보고 안내를 띄운다.
        res.put("pan", true);
        call.resolve(res);
    }

    @PluginMethod
    public void speak(PluginCall call) {
        String text = call.getString("text", "");
        if (!ready || text == null || text.trim().isEmpty()) {
            JSObject res = new JSObject();
            res.put("spoken", false);
            call.resolve(res);
            return;
        }
        Locale loc = toLocale(call.getString("lang", "ko-KR"));
        // 그 언어 음성이 없으면 읽지 않는다. 엉뚱한 발음으로 읽는 것보다 조용한 편이 낫다.
        if (!supports(loc)) {
            JSObject res = new JSObject();
            res.put("spoken", false);
            res.put("reason", "no-voice");
            call.resolve(res);
            return;
        }
        tts.setLanguage(loc);
        Float rate = call.getFloat("rate", 1.0f);
        tts.setSpeechRate(rate == null ? 1.0f : rate);

        Float panIn = call.getFloat("pan", 0f);
        float pan = panIn == null ? 0f : Math.max(-1f, Math.min(1f, panIn));
        String id = "mn" + (++counter);

        if (pan != 0f) {
            // 좌우로 갈라 보낸다. 파일로 받아 우리가 직접 재생한다.
            panCancel = false;
            File out = new File(getContext().getCacheDir(), "tts-" + id + ".wav");
            panJobs.put(id, new PanJob(out, pan < 0f));
            int rc = tts.synthesizeToFile(text, new Bundle(), out, id);
            if (rc != TextToSpeech.SUCCESS) { panJobs.remove(id); out.delete(); }
            JSObject res = new JSObject();
            res.put("spoken", rc == TextToSpeech.SUCCESS);
            res.put("id", id);
            call.resolve(res);
            return;
        }

        // QUEUE_ADD: 앞의 문장을 자르지 않고 이어서 읽는다. 회의 흐름이 끊기면 안 된다.
        int rc = tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), id);
        JSObject res = new JSObject();
        res.put("spoken", rc == TextToSpeech.SUCCESS);
        res.put("id", id);
        call.resolve(res);
    }

    /**
     * 합성된 WAV 를 한쪽 채널로만 재생한다.
     * 한 번에 하나씩만 돈다(단일 스레드). 순서가 뒤섞이면 대화가 엉킨다.
     */
    private void playPanned(PanJob job) {
        AudioTrack track = null;
        try {
            byte[] wav = readAll(job.file);
            if (wav == null) return;

            int[] fmt = new int[3];              // 0 = 시작위치, 1 = 표본율, 2 = 채널수
            int len = parseWav(wav, fmt);
            if (len <= 0) return;
            int off = fmt[0], rate = fmt[1], ch = fmt[2];
            if (rate <= 0) rate = 22050;

            // 한쪽 채널만 소리를 담은 스테레오로 엮는다. 반대쪽은 완전한 0 이다.
            int frames = ch == 2 ? len / 4 : len / 2;
            byte[] pcm = new byte[frames * 4];
            for (int f = 0, o = 0; f < frames; f++, o += 4) {
                int i = off + (ch == 2 ? f * 4 : f * 2);
                if (i + 1 >= wav.length) break;
                // 스테레오로 나오는 엔진이면 앞 채널만 쓴다. 두 채널을 섞으면 위상이 상한다.
                byte lo = wav[i], hi = wav[i + 1];
                if (job.left) { pcm[o] = lo; pcm[o + 1] = hi; pcm[o + 2] = 0; pcm[o + 3] = 0; }
                else          { pcm[o] = 0;  pcm[o + 1] = 0;  pcm[o + 2] = lo; pcm[o + 3] = hi; }
            }

            lastPan = (job.left ? "왼쪽" : "오른쪽") + " · " + rate + "Hz · 원본 "
                    + ch + "채널 · " + frames + "프레임";

            int min = AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = 8192;
            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            /* 말(SPEECH)이 아니라 음악(MUSIC)이라고 알린다.

                               말이라고 하면 폰이 알아듣기 좋게 만들어 준다며 손을 댄다.
                               삼성의 음성 보정과 적응형 사운드가 그 자리에서 걸리고,
                               그중 하나가 좌우를 섞는다. 한쪽을 0 으로 채워 보내도
                               양쪽에서 들리는 까닭이 이것이다. 유튜브는 음악으로 보내서
                               한쪽씩만 들린다. 우리도 그렇게 보낸다. */
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    Math.max(min, 16384), AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            panTrack = track;
            track.play();

            int written = 0;
            while (written < pcm.length && !panCancel) {
                int w = track.write(pcm, written, Math.min(4096, pcm.length - written));
                if (w <= 0) break;
                written += w;
            }
            // 버퍼에 남은 것까지 다 나갈 때까지 기다린다. 안 기다리면 끝말이 잘린다.
            int total = pcm.length / 4;
            while (!panCancel && track.getPlaybackHeadPosition() < total) {
                Thread.sleep(20);
            }
            AudioDeviceInfo out = track.getRoutedDevice();
            lastPan += " · 나간 곳 " + (out == null ? "모름" : typeName(out.getType()))
                     + " · 쓴 채널 " + track.getChannelCount();
        } catch (Exception e) {
            // 재생에 실패해도 회의는 계속되어야 한다. 조용히 넘어간다.
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) { }
                try { track.release(); } catch (Exception ignored) { }
            }
            panTrack = null;
            if (job.file != null) job.file.delete();
            emit("pan", false);
        }
    }

    private byte[] readAll(File f) {
        if (f == null || !f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return n > 0 ? b : null;
        } catch (Exception e) { return null; }
    }

    /**
     * WAV 머리를 읽어 소리가 시작되는 자리와 표본율·채널수를 찾는다.
     * 엔진마다 머리 길이가 다르므로 44 로 못 박지 않고 data 덩이를 찾아간다.
     * 반환값은 소리 바이트 길이. 못 읽으면 0.
     */
    private int parseWav(byte[] b, int[] out) {
        if (b == null || b.length < 44) return 0;
        if (b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return 0;
        int p = 12, rate = 0, ch = 1;
        while (p + 8 <= b.length) {
            int id = le32(b, p);
            int sz = le32(b, p + 4);
            if (sz < 0) break;
            if (id == 0x20746d66) {                       // "fmt "
                if (p + 16 <= b.length) {
                    ch = le16(b, p + 10);
                    rate = le32(b, p + 12);
                }
            } else if (id == 0x61746164) {                // "data"
                out[0] = p + 8;
                out[1] = rate;
                out[2] = ch < 1 ? 1 : ch;
                int len = Math.min(sz, b.length - (p + 8));
                return Math.max(0, len);
            }
            p += 8 + sz + (sz & 1);
        }
        return 0;
    }

    private int le16(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8);
    }

    private int le32(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8)
             | ((b[i + 2] & 0xff) << 16) | ((b[i + 3] & 0xff) << 24);
    }

    /**
     * 소리가 실제로 어디로 어떻게 나가는지 있는 그대로 알려 준다.
     *
     * 좌우가 안 갈릴 때 짐작으로 고치면 두 번 세 번 헛수고를 한다.
     * 우리가 스테레오로 보냈는데도 합쳐진다면 원인은 우리 바깥에 있다 —
     * 통화 모드, 접근성의 모노 오디오, 이어폰 자체의 합치기 중 하나다.
     */
    @PluginMethod
    public void audioInfo(PluginCall call) {
        JSObject res = new JSObject();
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                res.put("mode", am.getMode());          // 0 이 보통, 3 이 통화
                res.put("scoOn", am.isBluetoothScoOn());
                StringBuilder outs = new StringBuilder();
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_TELEPHONY) continue;
                    if (outs.length() > 0) outs.append(", ");
                    outs.append(typeName(t));
                    int[] cc = d.getChannelCounts();
                    if (cc.length > 0) {
                        outs.append("(");
                        for (int i = 0; i < cc.length; i++) {
                            if (i > 0) outs.append("/");
                            outs.append(cc[i]);
                        }
                        outs.append("ch)");
                    }
                }
                res.put("outputs", outs.toString());
            }
        } catch (Exception e) { res.put("amError", String.valueOf(e.getMessage())); }
        try {
            // 접근성의 "모노 오디오". 켜져 있으면 무엇을 보내든 좌우가 합쳐진다.
            int mono = android.provider.Settings.System.getInt(
                    getContext().getContentResolver(), "master_mono", 0);
            res.put("monoAudio", mono == 1);
        } catch (Exception e) { res.put("monoAudio", "모름"); }
        res.put("lastPan", lastPan);
        call.resolve(res);
    }

    private String typeName(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:   return "폰 스피커";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:  return "수화부";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:     return "유선 이어폰(마이크 포함)";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:  return "유선 이어폰";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:    return "무선 이어폰(음악 모드)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:     return "무선 이어폰(통화 모드·모노)";
            case AudioDeviceInfo.TYPE_USB_HEADSET:       return "USB 이어폰";
            case AudioDeviceInfo.TYPE_USB_DEVICE:        return "USB 기기";
            default:
                if (android.os.Build.VERSION.SDK_INT >= 31
                        && t == AudioDeviceInfo.TYPE_BLE_HEADSET) return "무선 이어폰(LE)";
                return "기타(" + t + ")";
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        panCancel = true;
        for (PanJob j : panJobs.values()) if (j.file != null) j.file.delete();
        panJobs.clear();
        AudioTrack t = panTrack;
        if (t != null) { try { t.pause(); t.flush(); } catch (Exception ignored) { } }
        if (ready) tts.stop();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        panCancel = true;
        panPlayer.shutdownNow();
        AudioTrack t = panTrack;
        if (t != null) { try { t.pause(); t.flush(); t.release(); } catch (Exception ignored) { } }
        panTrack = null;
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        super.handleOnDestroy();
    }
}
