package com.handong.meetnote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 화면이 꺼져도 녹음을 유지하는 Foreground Service.
 *
 * ── 왜 MediaRecorder 가 아니라 AudioRecord 인가 ──
 * MediaRecorder 를 청크마다 stop/start 하면 전환 사이에 0.1~0.3초가 통째로 날아간다.
 * 1시간 회의에서 120번 전환하면 20초 넘게 사라지고, 하필 구간 경계라 단어가 잘린다.
 *
 * AudioRecord 는 마이크에서 PCM 을 끊김 없이 받는다. 스트림은 한 번도 멈추지 않고
 * 파일 경계만 소프트웨어로 나눈다. 따라서 구조적으로 유실 구간이 존재할 수 없다.
 *
 * 출력은 16kHz 모노 PCM16 WAV. STT API 가 가장 좋아하는 포맷이다.
 */
public class RecorderService extends Service {

    public static final String CHANNEL_ID = "meetnote_recording";
    public static final int NOTIF_ID = 8801;
    public static final int RATE = 16000;

    public static RecorderService instance;

    public interface ChunkListener { void onChunk(File file, int seq, long offsetMs, int durMs); }
    public interface ErrorListener { void onError(String message); }
    public static ChunkListener listener;
    public static ErrorListener errorListener;

    private AudioRecord audio;
    private Thread worker;
    private PowerManager.WakeLock wake;

    private volatile boolean running = false;
    private volatile boolean paused = false;

    private int seq = 0;
    private long chunkMs = 30000L;
    /** 이 길이까지는 무조건 채운다. 그 전에는 침묵이 있어도 자르지 않는다. */
    private long minChunkMs = 15000L;
    /** 침묵이 안 와도 이 길이를 넘으면 강제로 자른다. */
    private long maxChunkMs = 45000L;
    /** 이만큼 조용하면 문장이 끝난 것으로 본다. */
    private long silenceHoldMs = 700L;
    /** 적응형 잡음 바닥. 현장 소음 수준에 맞춰 스스로 따라간다. */
    private double noiseFloor = 900.0;
    /** 실제로 녹음된 오디오의 누적 길이. 일시정지 구간은 포함하지 않는다. */
    private volatile long recordedMs = 0;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        if (intent != null) {
            chunkMs = intent.getLongExtra("chunkMs", 30000L);
            minChunkMs = intent.getLongExtra("minChunkMs", chunkMs / 2);
            maxChunkMs = intent.getLongExtra("maxChunkMs", chunkMs * 3 / 2);
            silenceHoldMs = intent.getLongExtra("silenceHoldMs", 700L);
        }

        createChannel();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(false),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, buildNotification(false));
        }

        /* 화면이 꺼져도 CPU 는 깨어 있어야 녹음이 끊기지 않는다.
           화면은 켜 두지 않는다. 배터리를 그만큼만 쓴다. */
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meetnote:rec");
            wake.setReferenceCounted(false);
            wake.acquire();
        } catch (Exception ignored) { }

        instance = this;
        running = true;
        paused = false;
        seq = 0;
        recordedMs = 0;

        worker = new Thread(this::runLoop, "meetnote-recorder");
        worker.start();
        return START_STICKY;
    }

    /* ---------------- 알림 ---------------- */

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "회의 녹음", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(boolean isPaused) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(isPaused ? "녹음 일시정지" : "회의 녹음 중")
                .setContentText(isPaused ? "이어서 녹음하려면 앱을 열어라"
                                         : "화면을 꺼도 계속 녹음한다")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(paused));
    }

    /* ---------------- 녹음 루프 ---------------- */

    private void runLoop() {
        int min = AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = RATE * 2;
        int bufSize = Math.max(min * 4, RATE * 2);   // 넉넉히. 오버런에 의한 유실을 막는다.

        try {
            audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                emitError("마이크를 열 수 없다. 다른 앱이 쓰고 있는지 확인할 것.");
                stopSelf();
                return;
            }
            pinBuiltInMic();
            audio.startRecording();
        } catch (Exception e) {
            emitError("마이크 초기화 실패: " + e.getMessage());
            stopSelf();
            return;
        }

        final int minSamples = (int) (RATE * minChunkMs / 1000L);
        final int maxSamples = (int) (RATE * maxChunkMs / 1000L);
        final byte[] buf = new byte[4096];
        long silentMs = 0;
        noiseFloor = 900.0;

        File dir = new File(getCacheDir(), "segments");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

        RandomAccessFile raf = null;
        File cur = null;
        int curSamples = 0;
        boolean micActive = true;
        boolean routeChecked = false;

        try {
            while (running) {

                if (paused) {
                    // 마이크를 실제로 놓아준다. 통화 등 다른 앱이 쓸 수 있게.
                    if (micActive) {
                        try { audio.stop(); } catch (Exception ignored) {}
                        micActive = false;
                    }
                    // 진행 중이던 청크는 여기서 닫아 바로 전사에 넘긴다.
                    if (raf != null) {
                        closeChunk(raf, cur, curSamples);
                        raf = null; cur = null; curSamples = 0;
                    }
                    try { Thread.sleep(120); } catch (InterruptedException ie) { break; }
                    continue;
                }

                if (!micActive) {
                    try { audio.startRecording(); micActive = true; }
                    catch (Exception e) { emitError("녹음 재개 실패: " + e.getMessage()); break; }
                }

                if (raf == null) {
                    /* 이름에 순번을 넣는다. 화면이 잠든 사이 쌓인 파일을 나중에 회수할 때
                       이 순번으로 순서를 잡고 중복을 가린다. 자릿수를 맞춰 이름 순 정렬이
                       곧 시간 순이 되게 한다. */
                    cur = new File(dir, "seg_" + String.format(java.util.Locale.US, "%05d", seq)
                            + "_" + System.currentTimeMillis() + ".wav");
                    raf = new RandomAccessFile(cur, "rw");
                    raf.setLength(0);
                    raf.write(new byte[44]);      // 헤더 자리. 청크를 닫을 때 채운다.
                    curSamples = 0;
                }

                int n = audio.read(buf, 0, buf.length);
                if (n > 0) {
                    // 첫 소리가 들어온 뒤에 물어야 실제 경로가 잡혀 있다.
                    if (!routeChecked) { routeChecked = true; reportMicRoute(); }
                    raf.write(buf, 0, n);
                    curSamples += n / 2;

                    // ── 문장 경계에서 자르기 ──
                    // 시계로 자르면 단어 한가운데가 잘린다. 말이 끊긴 자리에서 자른다.
                    double rms = rms(buf, n);
                    if (rms < noiseFloor) noiseFloor = noiseFloor * 0.90 + rms * 0.10;
                    else                  noiseFloor = noiseFloor * 0.995 + rms * 0.005;
                    boolean quiet = rms < Math.max(220.0, noiseFloor * 2.2);
                    long bufMs = (long) (n / 2) * 1000L / RATE;
                    silentMs = quiet ? silentMs + bufMs : 0;

                    boolean sentenceEnd = curSamples >= minSamples && silentMs >= silenceHoldMs;
                    boolean tooLong = curSamples >= maxSamples;
                    if (sentenceEnd || tooLong) {
                        closeChunk(raf, cur, curSamples);
                        raf = null; cur = null; curSamples = 0; silentMs = 0;
                    }
                } else if (n < 0) {
                    emitError("마이크 읽기 오류 (" + n + ")");
                    break;
                }
            }
        } catch (Exception e) {
            emitError("녹음 중 오류: " + e.getMessage());
        } finally {
            if (raf != null) closeChunk(raf, cur, curSamples);
            try { if (audio != null) { if (micActive) audio.stop(); audio.release(); } }
            catch (Exception ignored) {}
            audio = null;
        }
    }

    /** 버퍼의 실효값. 말소리인지 침묵인지 판단하는 데 쓴다. */
    private static double rms(byte[] b, int n) {
        int count = n / 2;
        if (count == 0) return 0;
        long sum = 0;
        for (int i = 0; i < count; i++) {
            int s = (short) ((b[2*i] & 0xff) | (b[2*i+1] << 8));
            sum += (long) s * s;
        }
        return Math.sqrt((double) sum / count);
    }

    /** WAV 헤더를 채워 파일을 완성하고 리스너에 넘긴다. */
    private void closeChunk(RandomAccessFile raf, File f, int samples) {
        try {
            raf.seek(0);
            raf.write(wavHeader(samples * 2));
            raf.close();
        } catch (Exception e) {
            try { raf.close(); } catch (Exception ignored) {}
        }
        if (f == null) return;
        // 0.4초 미만은 의미 없는 조각이다. 전사 비용만 나가므로 버린다.
        if (samples < RATE * 2 / 5) { //noinspection ResultOfMethodCallIgnored
            f.delete(); return;
        }
        int durMs = (int) (samples * 1000L / RATE);
        long offset = recordedMs;
        recordedMs += durMs;
        if (listener != null) listener.onChunk(f, seq++, offset, durMs);
        else //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static byte[] wavHeader(int dataLen) {
        int total = 36 + dataLen, byteRate = RATE * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");   le32(h, 4, total);    put(h, 8, "WAVE");
        put(h, 12, "fmt ");  le32(h, 16, 16);      le16(h, 20, 1);
        le16(h, 22, 1);      le32(h, 24, RATE);    le32(h, 28, byteRate);
        le16(h, 32, 2);      le16(h, 34, 16);      put(h, 36, "data");
        le32(h, 40, dataLen);
        return h;
    }
    private static void put(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }
    private static void le32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o+1] = (byte)(v>>8); b[o+2] = (byte)(v>>16); b[o+3] = (byte)(v>>24);
    }
    private static void le16(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o+1] = (byte)(v>>8);
    }

    /**
     * 폰에 붙은 마이크로 못 박는다.
     *
     * 무선 이어폰에는 마이크가 달려 있다. 그냥 두면 안드로이드가 그쪽을 잡는데,
     * 그러면 이어폰이 통화 모드(HFP)로 내려간다. 통화 모드에는 좌우가 없다 —
     * 대화 모드의 좌우 나눠 듣기가 통째로 무너진다. 소리도 좁은 대역으로 눌려
     * 전사 정확도까지 떨어진다. 마주 앉은 회의에서는 상 위의 폰 마이크가 낫다.
     *
     * 이것은 부탁이지 강제가 아니다. 그래서 reportMicRoute() 로 결과를 확인한다.
     */
    private void pinBuiltInMic() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null || audio == null) return;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    audio.setPreferredDevice(d);
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    /**
     * 못 박기가 먹혔는지 확인해 알려 준다.
     * 말없이 이어폰 마이크로 녹음되면, 왜 좌우가 안 갈리는지 알 길이 없다.
     */
    private void reportMicRoute() {
        try {
            if (audio == null) return;
            AudioDeviceInfo d = audio.getRoutedDevice();
            if (d == null) return;
            int t = d.getType();
            boolean bt = t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                      || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                      || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET);
            if (bt) emitError("이어폰 마이크로 녹음되고 있습니다. 이러면 이어폰이 통화 모드로 "
                    + "내려가 좌우 나눠 듣기가 되지 않고, 소리도 눌려 전사가 나빠집니다. "
                    + "이어폰 설정에서 통화용 마이크 사용을 끄거나, 유선 이어폰을 쓰거나, "
                    + "\"상대 말만 읽기\"로 바꾸어 주세요.");
        } catch (Exception ignored) { }
    }

    private void emitError(String msg) {
        if (errorListener != null) errorListener.onError(msg);
    }

    /* ---------------- 외부 제어 ---------------- */

    public void pause() {
        if (!running || paused) return;
        paused = true;
        updateNotification();
    }

    public void resume() {
        if (!running || !paused) return;
        paused = false;
        updateNotification();
    }

    public boolean isPaused() { return paused; }
    public long getRecordedMs() { return recordedMs; }

    public void stopAll() {
        running = false;
        paused = false;
        if (worker != null) {
            try { worker.join(2500); } catch (InterruptedException ignored) {}
            worker = null;
        }
        releaseWake();
        stopForeground(true);
        stopSelf();
        instance = null;
    }

    private void releaseWake() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        wake = null;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (audio != null) { audio.release(); audio = null; } } catch (Exception ignored) {}
        releaseWake();
        instance = null;
        super.onDestroy();
    }
}
