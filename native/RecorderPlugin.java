package com.handong.meetnote;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileInputStream;

/**
 * JS 쪽 사용법:
 *   const { Recorder } = Capacitor.Plugins;
 *   Recorder.addListener('chunk', ev => { ev.seq, ev.offsetMs, ev.durMs, ev.data(base64 wav) });
 *   Recorder.addListener('recError', ev => ev.message);
 *   await Recorder.start({ chunkMs: 30000 });
 *   await Recorder.pause();  await Recorder.resume();  await Recorder.stop();
 */
@CapacitorPlugin(
    name = "Recorder",
    permissions = {
        @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "mic"),
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notif")
    }
)
public class RecorderPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            call.setKeepAlive(true);
            requestPermissionForAlias("mic", call, "micResult");
            return;
        }
        askNotifThenLaunch(call);
    }

    @PermissionCallback
    private void micResult(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            call.reject("마이크 권한이 없다. 설정에서 허용할 것.");
            return;
        }
        askNotifThenLaunch(call);
    }

    private void askNotifThenLaunch(PluginCall call) {
        // Android 13+ 는 알림 권한이 없으면 Foreground Service 알림이 안 보인다.
        // 녹음 자체는 되므로 거절해도 진행한다.
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notif") != PermissionState.GRANTED) {
            call.setKeepAlive(true);
            requestPermissionForAlias("notif", call, "notifResult");
            return;
        }
        launch(call);
    }

    @PermissionCallback
    private void notifResult(PluginCall call) { launch(call); }

    private void launch(PluginCall call) {
        long chunkMs = call.getLong("chunkMs", 30000L);

        /* 화면이 꺼지면 안드로이드가 화면 쪽(WebView)을 재운다. 예전에는 여기서 파일을
           읽어 바로 지우고 이벤트만 보냈는데, 그 사이 이벤트가 전달되지 않으면 그 구간이
           통째로 사라졌다. 이제는 파일을 남겨 둔다. 화면이 깨어나 pending() 으로 가져가고
           discard() 로 지운다. 잠든 동안 녹음한 것은 그대로 디스크에 쌓인다. */
        RecorderService.listener = (file, seq, offsetMs, durMs) -> {
            JSObject ev = new JSObject();
            ev.put("seq", seq);
            ev.put("offsetMs", offsetMs);
            ev.put("durMs", durMs);
            ev.put("name", file.getName());
            notifyListeners("chunk", ev);
        };

        RecorderService.errorListener = message -> {
            JSObject err = new JSObject();
            err.put("message", message);
            notifyListeners("recError", err);
        };

        Intent i = new Intent(getContext(), RecorderService.class);
        i.putExtra("chunkMs", chunkMs);
        i.putExtra("minChunkMs",   call.getLong("minChunkMs",   chunkMs / 2));
        i.putExtra("maxChunkMs",   call.getLong("maxChunkMs",   chunkMs * 3 / 2));
        i.putExtra("silenceHoldMs", call.getLong("silenceHoldMs", 700L));
        if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i);
        else getContext().startService(i);

        call.setKeepAlive(false);
        call.resolve();
    }

    /* 녹음 중에는 화면을 켜 둔다.
       화면이 꺼지면 안드로이드가 화면 쪽(WebView)을 재워 전사와 번역이 멈춘다.
       녹음 자체는 이어지지만 자막은 끊긴다. 켜 두면 그 문제가 통째로 사라진다.
       대신 배터리를 쓴다. 그래서 설정에서 끌 수 있게 두었다. */
    @PluginMethod
    public void keepAwake(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        final android.app.Activity act = getActivity();
        if (act == null) { call.resolve(); return; }
        act.runOnUiThread(() -> {
            if (on) act.getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else act.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        call.resolve();
    }

    /**
     * 화면 밝기를 바닥까지 내린다. 끄지는 않는다.
     *
     * 녹음 중에는 화면이 꺼지면 전사가 멈추므로 켜 두어야 한다. 그런데 화면을
     * 봐야 해서 켜 두는 것이 아니다. 그래서 켜 둔 채로 밝기만 최소로 내린다.
     * 화면이 새까맣게 덮여 있으므로 볼 것도 없다. 배터리는 이쪽에서 크게 준다.
     *
     * 이 창에만 적용되는 밝기다. 앱을 나가면 저절로 기기 설정으로 돌아간다.
     */
    @PluginMethod
    public void screenDim(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        final android.app.Activity act = getActivity();
        if (act == null) { call.resolve(); return; }
        act.runOnUiThread(() -> {
            android.view.Window w = act.getWindow();
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = on
                    ? 0.01f   // 거의 꺼진 것처럼 어둡게
                    : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            w.setAttributes(lp);
        });
        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        if (RecorderService.instance != null) RecorderService.instance.pause();
        call.resolve();
    }

    @PluginMethod
    public void resume(PluginCall call) {
        if (RecorderService.instance != null) RecorderService.instance.resume();
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (RecorderService.instance != null) RecorderService.instance.stopAll();
        RecorderService.listener = null;
        RecorderService.errorListener = null;
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        RecorderService s = RecorderService.instance;
        JSObject r = new JSObject();
        r.put("recording", s != null);
        r.put("paused", s != null && s.isPaused());
        r.put("recordedMs", s == null ? 0 : s.getRecordedMs());
        call.resolve(r);
    }

    /* 아직 화면이 가져가지 않은 구간들. 이름 순으로 돌려준다.
       화면이 깨어날 때마다 이것을 물어 밀린 것을 회수한다. */
    @PluginMethod
    public void pending(PluginCall call) {
        File dir = new File(getContext().getCacheDir(), "segments");
        File[] files = dir.listFiles();
        JSArray out = new JSArray();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : files) {
                JSObject o = new JSObject();
                o.put("name", f.getName());
                o.put("size", f.length());
                out.put(o);
            }
        }
        JSObject r = new JSObject();
        r.put("files", out);
        call.resolve(r);
    }

    /* 구간 하나를 읽어 준다. 화면이 저장한 뒤 discard 로 지운다. */
    @PluginMethod
    public void read(PluginCall call) {
        String name = call.getString("name", "");
        JSObject r = new JSObject();
        try {
            File f = new File(new File(getContext().getCacheDir(), "segments"), name);
            if (!f.exists()) { r.put("missing", true); call.resolve(r); return; }
            byte[] bytes = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int read = 0, n;
            while (read < bytes.length && (n = in.read(bytes, read, bytes.length - read)) > 0) read += n;
            in.close();
            r.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
            call.resolve(r);
        } catch (Exception e) {
            call.reject("구간을 읽지 못했다: " + e.getMessage());
        }
    }

    @PluginMethod
    public void discard(PluginCall call) {
        File f = new File(new File(getContext().getCacheDir(), "segments"), call.getString("name", ""));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        call.resolve();
    }

    @PluginMethod
    public void clearCache(PluginCall call) {
        File dir = new File(getContext().getCacheDir(), "segments");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        call.resolve();
    }
}
