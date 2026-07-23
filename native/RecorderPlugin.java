package com.handong.meetnote;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;

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

        RecorderService.listener = (file, seq, offsetMs, durMs) -> {
            try {
                byte[] bytes = new byte[(int) file.length()];
                FileInputStream in = new FileInputStream(file);
                int read = 0, n;
                while (read < bytes.length && (n = in.read(bytes, read, bytes.length - read)) > 0) read += n;
                in.close();
                //noinspection ResultOfMethodCallIgnored
                file.delete();

                JSObject ev = new JSObject();
                ev.put("seq", seq);
                ev.put("offsetMs", offsetMs);
                ev.put("durMs", durMs);
                ev.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
                notifyListeners("chunk", ev);
            } catch (Exception e) {
                JSObject err = new JSObject();
                err.put("message", "청크 전달 실패 (" + seq + "): " + e.getMessage());
                notifyListeners("recError", err);
            }
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

    @PluginMethod
    public void clearCache(PluginCall call) {
        File dir = new File(getContext().getCacheDir(), "segments");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        call.resolve();
    }
}
