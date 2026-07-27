package com.handong.meetnote;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 파일을 폰의 다운로드 폴더에 저장한다.
 *
 * 왜 필요한가: 앱 안의 WebView 는 브라우저식 다운로드가 막혀 있다. 링크를 만들어
 * 눌러도 아무 일이 일어나지 않는다. 그래서 백업을 내보냈다는 말만 뜨고 파일은
 * 어디에도 남지 않았다. 네이티브로 직접 써야 한다.
 *
 * 안드로이드 10 이상은 MediaStore 로 쓴다. 권한이 필요 없다.
 * 그 아래 버전은 앱 전용 외부 폴더에 쓴다. 역시 권한이 필요 없다.
 *
 * JS 쪽 사용법:
 *   const { Saver } = Capacitor.Plugins;
 *   await Saver.save({ name:"meetnote-2026-07-27.json", mime:"application/json", data: base64 });
 */
@CapacitorPlugin(name = "Saver")
public class SaverPlugin extends Plugin {

    @PluginMethod
    public void save(PluginCall call) {
        String name = call.getString("name", "meetnote.json");
        String mime = call.getString("mime", "application/octet-stream");
        String b64  = call.getString("data", "");
        if (b64 == null || b64.isEmpty()) { call.reject("빈 파일이다."); return; }

        byte[] bytes;
        try { bytes = Base64.decode(b64, Base64.DEFAULT); }
        catch (Exception e) { call.reject("파일을 해석하지 못했다: " + e.getMessage()); return; }

        try {
            String where;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContext().getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) { call.reject("저장할 자리를 잡지 못했다."); return; }
                OutputStream out = getContext().getContentResolver().openOutputStream(uri);
                if (out == null) { call.reject("파일을 열지 못했다."); return; }
                out.write(bytes);
                out.close();
                where = "다운로드 폴더";
            } else {
                File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && !dir.exists()) //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream out = new FileOutputStream(f);
                out.write(bytes);
                out.close();
                where = f.getAbsolutePath();
            }
            JSObject r = new JSObject();
            r.put("saved", true);
            r.put("where", where);
            r.put("name", name);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("저장하지 못했다: " + e.getMessage());
        }
    }
}
