#!/usr/bin/env node
/**
 * `npx cap add android` 로 생성된 안드로이드 프로젝트에 네이티브 녹음 코드를 주입한다.
 * android/ 는 빌드 산출물이라 리포에 커밋하지 않는다. 이 스크립트가 매번 재현한다.
 *
 * 하는 일
 *   1. native/*.java 를 앱 패키지로 복사
 *   2. MainActivity 에 registerPlugin 삽입
 *   3. AndroidManifest 에 권한과 service 선언 삽입
 *
 * 멱등하다. 여러 번 돌려도 중복되지 않는다.
 */
const fs = require("fs");
const path = require("path");

const PKG = "com.handong.meetnote";
const ROOT = path.resolve(__dirname, "..");
const APP = path.join(ROOT, "android", "app", "src", "main");
const JAVA_DIR = path.join(APP, "java", ...PKG.split("."));
const MANIFEST = path.join(APP, "AndroidManifest.xml");

function fail(msg) { console.error("✗ " + msg); process.exit(1); }
function ok(msg) { console.log("✓ " + msg); }

if (!fs.existsSync(APP)) fail("android/ 가 없다. 먼저 `npx cap add android` 를 실행할 것.");

/* ---------- 1. 자바 소스 복사 ---------- */
fs.mkdirSync(JAVA_DIR, { recursive: true });
for (const f of ["RecorderService.java", "RecorderPlugin.java", "TtsPlugin.java", "SaverPlugin.java"]) {
  fs.copyFileSync(path.join(ROOT, "native", f), path.join(JAVA_DIR, f));
  ok("복사 " + f);
}

/* ---------- 2. MainActivity 에 플러그인 등록 ---------- */
const mainJava = path.join(JAVA_DIR, "MainActivity.java");
const mainKt = path.join(JAVA_DIR, "MainActivity.kt");

if (fs.existsSync(mainJava)) {
  let src = fs.readFileSync(mainJava, "utf8");
  if (src.includes("RecorderPlugin.class") && src.includes("TtsPlugin.class") && src.includes("SaverPlugin.class")) {
    ok("MainActivity 이미 등록됨");
  } else if (/public\s+void\s+onCreate\s*\(/.test(src)) {
    let ins = "";
    if (!src.includes("RecorderPlugin.class")) ins += "\n        registerPlugin(RecorderPlugin.class);";
    if (!src.includes("TtsPlugin.class"))      ins += "\n        registerPlugin(TtsPlugin.class);";
    if (!src.includes("SaverPlugin.class"))    ins += "\n        registerPlugin(SaverPlugin.class);";
    src = src.replace(/(public\s+void\s+onCreate\s*\([^)]*\)\s*\{)/, "$1" + ins);
    fs.writeFileSync(mainJava, src);
    ok("MainActivity onCreate 에 registerPlugin 삽입");
  } else {
    // Capacitor 기본 MainActivity 는 onCreate 가 비어 있다. 통째로 교체한다.
    fs.writeFileSync(mainJava,
`package ${PKG};

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RecorderPlugin.class);
        registerPlugin(TtsPlugin.class);
        registerPlugin(SaverPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
`);
    ok("MainActivity 재작성");
  }
} else if (fs.existsSync(mainKt)) {
  let src = fs.readFileSync(mainKt, "utf8");
  if (!src.includes("RecorderPlugin::class.java") || !src.includes("TtsPlugin::class.java") || !src.includes("SaverPlugin::class.java")) {
    fs.writeFileSync(mainKt,
`package ${PKG}

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(RecorderPlugin::class.java)
        registerPlugin(TtsPlugin::class.java)
        registerPlugin(SaverPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
`);
    ok("MainActivity.kt 재작성");
  } else ok("MainActivity.kt 이미 등록됨");
} else fail("MainActivity 를 찾을 수 없다: " + JAVA_DIR);

/* ---------- 3. 매니페스트 패치 ---------- */
let mf = fs.readFileSync(MANIFEST, "utf8");

const PERMS = [
  "android.permission.RECORD_AUDIO",
  "android.permission.FOREGROUND_SERVICE",
  "android.permission.FOREGROUND_SERVICE_MICROPHONE",
  "android.permission.POST_NOTIFICATIONS",
  "android.permission.INTERNET",
  "android.permission.ACCESS_NETWORK_STATE",
  "android.permission.WAKE_LOCK",
];
let added = [];
for (const p of PERMS) {
  if (mf.includes(`"${p}"`)) continue;
  mf = mf.replace(/<\/manifest>/, `    <uses-permission android:name="${p}" />\n</manifest>`);
  added.push(p.split(".").pop());
}
ok(added.length ? "권한 추가: " + added.join(", ") : "권한 이미 있음");

if (!mf.includes("RecorderService")) {
  const service =
`        <service
            android:name=".RecorderService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="microphone" />
`;
  if (!/<\/application>/.test(mf)) fail("매니페스트에 </application> 이 없다.");
  mf = mf.replace(/<\/application>/, service + "    </application>");
  ok("RecorderService 선언 삽입");
} else ok("RecorderService 이미 선언됨");

fs.writeFileSync(MANIFEST, mf);
console.log("\n네이티브 주입 완료. 이제 android/ 에서 gradlew assembleDebug 를 돌리면 된다.");
