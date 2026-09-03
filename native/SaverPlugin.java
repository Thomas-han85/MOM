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
 * 이어 쓰기가 있는 까닭.
 *   90분 녹음은 173MB 다. 그것을 한 번에 넘기려면 base64 로 부풀린 글자열을
 *   자바스크립트가 통째로 쥐어야 하고, 그러면 1GB 가까이 되어 앱이 꺼진다.
 *   실제로 두 번 꺼졌고 그때 회의 기록이 날아갔다.
 *   그래서 예전에는 10분씩 잘라 여러 파일로 냈는데, 받는 사람이 조각을 이어
 *   들어야 해서 쓸모가 떨어졌다.
 *   이제는 조각을 그대로 넘기되 네이티브가 한 파일에 이어 붙인다.
 *   자바스크립트는 한 번에 한 조각(약 19MB)만 쥔다.
 *
 * JS 쪽 사용법:
 *   await Saver.save({ name:"a.wav", mime:"audio/wav", data: base64 });            // 통째로
 *   await Saver.save({ name:"a.wav", mime:"audio/wav", data: b64, append:"start" });// 첫 조각
 *   await Saver.save({ name:"a.wav", data: b64, append:"more" });                   // 이어서
 *   await Saver.save({ name:"a.wav", data: b64, append:"end" });                    // 마지막
 */
@CapacitorPlugin(name = "Saver")
public class SaverPlugin extends Plugin {

    /* 이어 쓰는 동안 열어 두는 것. 한 번에 하나만 연다 —
       두 개를 동시에 쓰는 화면이 없고, 두면 어느 것이 열려 있는지 헷갈린다. */
    private OutputStream openOut = null;
    private String openName = null;
    private String openWhere = null;

    private void closeOpen() {
        if (openOut != null) {
            try { openOut.flush(); } catch (Exception ignored) {}
            try { openOut.close(); } catch (Exception ignored) {}
        }
        openOut = null; openName = null; openWhere = null;
    }

    /** 파일을 새로 만들어 연다. 어디에 만들었는지 돌려준다. */
    private String openNew(String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContext().getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("저장할 자리를 잡지 못했습니다.");
            openOut = getContext().getContentResolver().openOutputStream(uri);
            if (openOut == null) throw new Exception("파일을 열지 못했습니다.");
            return "다운로드 폴더";
        }
        File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null && !dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        File f = new File(dir, name);
        openOut = new FileOutputStream(f);
        return f.getAbsolutePath();
    }

    @PluginMethod
    public void save(PluginCall call) {
        String name   = call.getString("name", "meetnote.json");
        String mime   = call.getString("mime", "application/octet-stream");
        String b64    = call.getString("data", "");
        String append = call.getString("append", "");   // "" | start | more | end

        if (b64 == null || b64.isEmpty()) { call.reject("빈 파일입니다."); return; }

        byte[] bytes;
        try { bytes = Base64.decode(b64, Base64.DEFAULT); }
        catch (Exception e) { call.reject("파일을 해석하지 못했습니다: " + e.getMessage()); return; }

        try {
            if (append == null || append.isEmpty()) {
                /* 통째로 쓰는 예전 길. 백업 파일과 회의록이 이 길을 쓴다. */
                closeOpen();
                String where = openNew(name, mime);
                openOut.write(bytes);
                closeOpen();
                JSObject r = new JSObject();
                r.put("saved", true); r.put("where", where); r.put("name", name);
                call.resolve(r);
                return;
            }

            if ("start".equals(append)) {
                closeOpen();                       // 앞서 쓰다 만 것이 있으면 닫는다
                openWhere = openNew(name, mime);
                openName = name;
            } else if (openOut == null || !name.equals(openName)) {
                /* 이어 쓰라는데 열린 것이 없거나 다른 이름이다. 조용히 새로 만들면
                   앞부분 없는 파일이 남는다. 무슨 일인지 말해 준다. */
                call.reject("이어 쓸 파일이 열려 있지 않습니다. 처음부터 다시 저장해 주세요.");
                return;
            }

            openOut.write(bytes);

            JSObject r = new JSObject();
            r.put("saved", "end".equals(append));
            r.put("where", openWhere);
            r.put("name", openName);
            if ("end".equals(append)) closeOpen();
            call.resolve(r);

        } catch (Exception e) {
            closeOpen();
            call.reject("저장하지 못했습니다: " + e.getMessage());
        }
    }

    /** 쓰다 만 것을 닫는다. 화면을 벗어나거나 실패했을 때 부른다. */
    @PluginMethod
    public void cancel(PluginCall call) {
        closeOpen();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        closeOpen();
    }
}
