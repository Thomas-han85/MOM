package com.handong.meetnote;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.Locale;

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
 *   await Tts.available();                                  // { ready, langs:{ko,vi,en} }
 *   await Tts.speak({ text:"...", lang:"ko-KR", rate:1.0 }); // 큐에 쌓아 순서대로 읽는다
 *   await Tts.stop();
 */
@CapacitorPlugin(name = "Tts")
public class TtsPlugin extends Plugin {

    private TextToSpeech tts;
    private volatile boolean ready = false;
    private int counter = 0;

    @Override
    public void load() {
        tts = new TextToSpeech(getContext(), status -> {
            ready = (status == TextToSpeech.SUCCESS);
            if (ready) {
                tts.setLanguage(Locale.KOREAN);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { emit(id, false); }
                    @Override public void onError(String id) { emit(id, true); }
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

        String id = "mn" + (++counter);
        // QUEUE_ADD: 앞의 문장을 자르지 않고 이어서 읽는다. 회의 흐름이 끊기면 안 된다.
        int rc = tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), id);
        JSObject res = new JSObject();
        res.put("spoken", rc == TextToSpeech.SUCCESS);
        res.put("id", id);
        call.resolve(res);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (ready) tts.stop();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        super.handleOnDestroy();
    }
}
