# 구글드라이브 연동 준비 — 톰이 할 일

폰(APK)과 랩탑(브라우저)이 같은 구글계정의 Drive 폴더로 동기화된다.
로그인은 기기당 1회, 이후 계속 유지된다. 데이터는 톰의 Drive 에만 있다.

코드는 준비되는 대로 붙인다. 그 전에 아래 세 가지가 필요하다. 약 15분.

---

## 1. GitHub secret 등록 (5분) — 고정 서명키

빌드마다 서명키가 바뀌면 구글 OAuth 가 SHA-1 불일치로 깨진다.
고정 키스토어를 만들어 뒀다: `keys/debug.keystore` (깃허브엔 안 올라간다).

1. 리포 페이지 → **Settings → Secrets and variables → Actions → New repository secret**
2. Name: `DEBUG_KEYSTORE_B64`
3. Secret: `keys/keystore-base64.txt` 파일을 열어 내용 전체를 붙여넣기
4. 저장

`keys/` 폴더는 지우지 말 것. 이 키가 곧 앱의 신분증이다.
(`keys/meetnote-release.keystore` 는 처음에 잘못 만든 것 — 지워도 된다.)

**주의: 다음 APK 는 서명이 바뀌므로, 딱 한 번 기존 앱을 지우고 새로 설치해야 한다.**
데이터가 날아가니 설치 전에 설정 → 백업 내보내기부터.

## 2. GitHub Pages 켜기 (2분) — 랩탑 접속용

랩탑은 앱 설치 없이 브라우저로 쓴다.

1. 리포 → **Settings → Pages**
2. Source: **Deploy from a branch** → Branch: `main`, 폴더 `/ (root)`
3. 몇 분 뒤 생기는 주소 확인: `https://<깃허브계정>.github.io/<리포이름>/`

## 3. Google Cloud OAuth 클라이언트 (10분)

https://console.cloud.google.com 접속 (handong1315@gmail.com 으로).

### 3-1. 프로젝트 + API
1. 상단 프로젝트 선택 → **새 프로젝트** → 이름 `meetnote` → 만들기
2. **API 및 서비스 → 라이브러리** → "Google Drive API" 검색 → **사용 설정**

### 3-2. OAuth 동의 화면
1. **API 및 서비스 → OAuth 동의 화면** → User Type: **외부(External)** → 만들기
2. 앱 이름 `대역`, 사용자 지원 이메일·개발자 이메일: handong1315@gmail.com → 저장 계속
3. 범위(Scope)·테스트 사용자는 건너뛰어도 된다 → 완료
4. **중요:** 대시보드에서 **"앱 게시(Publish app)" → 프로덕션으로 전환**.
   테스트 모드로 두면 로그인이 7일마다 풀린다. "한번 연결하면 계속"의 핵심이 이 버튼이다.
   (우리가 쓰는 drive.file 범위는 비민감이라 심사 없이 게시된다.)

### 3-3. 클라이언트 2개 생성
**API 및 서비스 → 사용자 인증 정보 → 사용자 인증 정보 만들기 → OAuth 클라이언트 ID**

**① 웹 애플리케이션** (랩탑용)
- 이름: `meetnote-web`
- 승인된 자바스크립트 원본: `https://<깃허브계정>.github.io`  ← 2번에서 확인한 도메인
- 만들기 → **클라이언트 ID 복사**

**② Android** (폰 APK용)
- 이름: `meetnote-apk`
- 패키지 이름: `com.handong.meetnote`
- SHA-1 인증서 디지털 지문:
  `96:00:1A:75:A3:7B:B8:A8:64:A6:AB:91:D4:EB:D1:AB:11:78:97:86`
- 만들기 → **클라이언트 ID 복사**

---

## 끝나면 나한테 알려줄 것 세 가지

1. 웹 클라이언트 ID (`....apps.googleusercontent.com`)
2. Android 클라이언트 ID
3. GitHub Pages 주소

이 세 개 받으면 동기화 코드 붙인다. 클라이언트 ID 는 비밀이 아니라 코드에 넣어도 된다.
