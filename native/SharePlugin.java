package com.handong.meetnote;

import android.content.Intent;
import android.net.Uri;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 회의록을 다른 앱으로 보낸다. 카카오톡, 잘로, 메일, 무엇이든 안드로이드가
 * 목록에 띄워 주는 앱으로 간다.
 *
 * 왜 필요한가: 앱 안의 WebView 에는 navigator.share 가 없다. 그래서 "공유" 를
 * 눌러도 공유창이 뜨지 않고 다운로드 폴더에 파일만 떨어졌다. 파일이 어디 있는지
 * 찾아 카톡에 첨부하는 일은 아무도 하지 않는다. 안드로이드의 공유창을 직접 띄운다.
 *
 * 글자로 보내는 것이 기본이다. 대화 앱에 파일을 붙이면 상대가 열기 번거롭고,
 * 회의록은 대개 그 자리에서 읽히기 때문이다.
 *
 * 다만 파일로 보내야 할 때가 있다. 회의록 전체가 대화창 하나에 안 들어가거나,
 * 받는 사람이 문서로 보관해야 할 때다. 그때는 uri 를 함께 넘긴다.
 * 저장할 때 만들어진 주소를 그대로 쓴다 — 파일을 또 만들지 않는다.
 * 주소를 안 넘기면 사람이 파일 관리자를 열어 찾아 붙여야 하고, 아무도 안 한다.
 *
 * 주의: 인텐트에 실어 보낼 수 있는 크기에 한계가 있다(대략 1MB, 실제로는 그보다
 * 작다). 넘치면 앱이 죽는다. 그래서 JS 쪽에서 미리 잘라 보내고, 여기서도 한 번 더 막는다.
 *
 * JS 쪽 사용법:
 *   const { Share } = Capacitor.Plugins;
 *   await Share.share({ title:"현장 주간회의", text:"..." });
 *   await Share.share({ title:"현장 주간회의", uri:"content://...", mime:"text/markdown" });
 */
@CapacitorPlugin(name = "Share")
public class SharePlugin extends Plugin {

    /** 인텐트로 넘길 글자 수 상한. 넘으면 자른다. */
    private static final int MAX_CHARS = 180000;

    @PluginMethod
    public void share(PluginCall call) {
        String title = call.getString("title", "");
        String text  = call.getString("text", "");

        if (text == null || text.trim().isEmpty()) {
            call.reject("보낼 내용이 없습니다.");
            return;
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n\n…(너무 길어 여기까지만 보냈습니다)";
        }

        try {
            String uriStr = call.getString("uri", "");
            String mime = call.getString("mime", "text/plain");

            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            if (title != null && !title.isEmpty()) {
                send.putExtra(Intent.EXTRA_SUBJECT, title);
                send.putExtra(Intent.EXTRA_TITLE, title);
            }

            if (uriStr != null && !uriStr.isEmpty()) {
                /* 파일을 함께 보낸다. 읽기 권한을 같이 넘기지 않으면 받는 앱이
                   주소만 받고 열지 못한다. 그러면 첨부가 빈 채로 나간다. */
                Uri u = Uri.parse(uriStr);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, u);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(android.content.ClipData.newRawUri("", u));
            }

            Intent chooser = Intent.createChooser(send, "회의록 보내기");
            // 액티비티 밖에서 띄우는 경우를 대비한다.
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooser);

            JSObject r = new JSObject();
            r.put("shared", true);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("보내지 못했습니다: " + e.getMessage());
        }
    }
}
