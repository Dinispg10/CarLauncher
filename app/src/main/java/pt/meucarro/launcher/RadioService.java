package pt.meucarro.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Toca as rádios online em segundo plano. Publica uma sessão de multimédia,
 * por isso a rádio aparece no ecrã "A tocar" e responde aos botões do volante.
 */
public class RadioService extends Service implements AudioManager.OnAudioFocusChangeListener {

    public static final String ACTION_PLAY = "pt.meucarro.launcher.radio.PLAY";
    public static final String ACTION_TOGGLE = "pt.meucarro.launcher.radio.TOGGLE";
    public static final String ACTION_STOP = "pt.meucarro.launcher.radio.STOP";
    public static final String ACTION_NEXT = "pt.meucarro.launcher.radio.NEXT";
    public static final String ACTION_PREV = "pt.meucarro.launcher.radio.PREV";
    public static final String EXTRA_INDEX = "index";

    public enum State {STOPPED, LOADING, PLAYING, ERROR}

    public interface Listener {
        void onRadioChanged();
    }

    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static int currentIndex = -1;
    private static State state = State.STOPPED;

    public static int currentIndex() {
        return currentIndex;
    }

    /** A rádio está a tocar (ou a ligar)? */
    public static boolean isOn() {
        return state == State.PLAYING || state == State.LOADING;
    }

    public static State state() {
        return state;
    }

    public static void addListener(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** Envia uma ordem ao serviço (tocar, parar, seguinte…). */
    public static void send(Context context, String action, int index) {
        Intent intent = new Intent(context, RadioService.class).setAction(action).putExtra(EXTRA_INDEX, index);
        boolean starts = ACTION_PLAY.equals(action) || ACTION_NEXT.equals(action) || ACTION_PREV.equals(action)
                || (ACTION_TOGGLE.equals(action) && state != State.PLAYING && state != State.LOADING);
        if (starts && Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL = "radio";
    private static final long CONNECT_TIMEOUT_MS = 12000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private int urlIndex;
    private int attempt; // para ignorar respostas de tentativas antigas
    private int retries;
    private static final int MAX_RETRIES = 6;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    /** Mantém o Wi-Fi em modo rápido enquanto a rádio toca (evita cortes por poupança de energia). */
    private void holdWifi(boolean on) {
        try {
            if (on) {
                if (wifiLock == null) {
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                            getApplicationContext().getSystemService(WIFI_SERVICE);
                    if (wm == null) return;
                    wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "radio");
                    wifiLock.setReferenceCounted(false);
                }
                wifiLock.acquire();
            } else if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, "RadioOnline");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play(Math.max(0, currentIndex));
            }

            @Override
            public void onPause() {
                stopPlayback(false);
            }

            @Override
            public void onStop() {
                stopPlayback(true);
            }

            @Override
            public void onSkipToNext() {
                play((Math.max(0, currentIndex) + 1) % RadioStations.ALL.length);
            }

            @Override
            public void onSkipToPrevious() {
                int n = RadioStations.ALL.length;
                play((Math.max(0, currentIndex) - 1 + n) % n);
            }
        });
        //noinspection deprecation
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Rádio online", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        int index = intent != null ? intent.getIntExtra(EXTRA_INDEX, -1) : -1;
        if (ACTION_PLAY.equals(action)) {
            play(index >= 0 ? index : Math.max(0, currentIndex));
        } else if (ACTION_TOGGLE.equals(action)) {
            if (state == State.PLAYING || state == State.LOADING) stopPlayback(false);
            else play(Math.max(0, currentIndex));
        } else if (ACTION_NEXT.equals(action)) {
            play((Math.max(0, currentIndex) + 1) % RadioStations.ALL.length);
        } else if (ACTION_PREV.equals(action)) {
            int n = RadioStations.ALL.length;
            play((Math.max(0, currentIndex) - 1 + n) % n);
        } else {
            stopPlayback(true);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        abandonFocus();
        session.setActive(false);
        session.release();
        state = State.STOPPED;
        notifyListeners();
        super.onDestroy();
    }

    // ---------- Tocar ----------

    private void play(int index) {
        if (index < 0 || index >= RadioStations.ALL.length) index = 0;
        releasePlayer();
        currentIndex = index;
        urlIndex = 0;
        retries = 0;
        AudioLog.add("Rádio: tocar " + RadioStations.ALL[index].name);
        holdWifi(true);
        setState(State.LOADING);
        startForegroundNow();
        if (!requestFocus()) {
            AudioLog.add("Rádio: o sistema recusou dar o som à rádio");
            setState(State.ERROR);
            return;
        }
        session.setActive(true);
        updateSession();
        MediaHub.get(this).pauseOthers(); // pára o Bluetooth/Spotify enquanto a rádio toca
        tryCurrentUrl();
    }

    private void tryCurrentUrl() {
        final RadioStations.Station station = RadioStations.ALL[currentIndex];
        if (urlIndex >= station.urls.length) {
            releasePlayer();
            setState(State.ERROR);
            // Sem internet (ex.: hotspot caiu): volta a tentar sozinha daqui a pouco
            if (retries < MAX_RETRIES) {
                retries++;
                AudioLog.add("Rádio: sem ligação, nova tentativa " + retries + "/" + MAX_RETRIES + " em 5 s");
                final int myAttempt = attempt;
                main.postDelayed(() -> {
                    if (myAttempt == attempt && state == State.ERROR) {
                        urlIndex = 0;
                        setState(State.LOADING);
                        tryCurrentUrl();
                    }
                }, 5000);
            } else {
                AudioLog.add("Rádio: desisti depois de " + MAX_RETRIES + " tentativas");
            }
            return;
        }
        AudioLog.add("Rádio: a ligar (" + (urlIndex + 1) + "/" + station.urls.length + ")");
        releasePlayer();
        final int myAttempt = ++attempt;
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setOnPreparedListener(mp -> {
            if (myAttempt != attempt) return;
            mp.start();
            AudioLog.add("Rádio: a tocar");
            setState(State.PLAYING);
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (myAttempt != attempt) return true;
            if (state == State.PLAYING && retries < MAX_RETRIES) {
                // A ligação caiu a meio: volta a ligar à mesma rádio
                retries++;
                AudioLog.add("Rádio: a ligação caiu (" + what + "/" + extra + "), a religar");
                urlIndex = 0;
                setState(State.LOADING);
                main.postDelayed(() -> {
                    if (myAttempt == attempt) tryCurrentUrl();
                }, 1500);
            } else {
                AudioLog.add("Rádio: erro na ligação (" + what + "/" + extra + ")");
                nextUrl();
            }
            return true;
        });
        // A carregar a meio (internet lenta): mostra "a ligar" e fica no registo
        player.setOnInfoListener((mp, what, extra) -> {
            if (myAttempt != attempt) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                AudioLog.add("Rádio: à espera de dados (internet lenta)");
                setState(State.LOADING);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                AudioLog.add("Rádio: dados recebidos, continua");
                setState(State.PLAYING);
            }
            return false;
        });
        try {
            player.setDataSource(station.urls[urlIndex]);
            player.prepareAsync();
        } catch (Exception e) {
            nextUrl();
            return;
        }
        // Se não ligar a tempo, passa para a ligação seguinte
        main.postDelayed(() -> {
            if (myAttempt == attempt && state == State.LOADING) nextUrl();
        }, CONNECT_TIMEOUT_MS);
    }

    private void nextUrl() {
        urlIndex++;
        tryCurrentUrl();
    }

    /** Para a rádio. Com destroy=true termina também o serviço. */
    private void stopPlayback(boolean destroy) {
        AudioLog.add("Rádio: parada" + (destroy ? "" : " (pausa)"));
        holdWifi(false);
        attempt++;
        releasePlayer();
        abandonFocus();
        setState(State.STOPPED);
        stopForeground(true);
        if (destroy) {
            session.setActive(false);
            stopSelf();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void setState(State next) {
        state = next;
        updateSession();
        notifyListeners();
    }

    private static void notifyListeners() {
        for (Listener l : new ArrayList<>(LISTENERS)) l.onRadioChanged();
    }

    // ---------- Foco de áudio (pausa o Bluetooth enquanto toca a rádio) ----------

    private boolean requestFocus() {
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(this)
                    .build();
            result = audio.requestAudioFocus(focusRequest);
        } else {
            //noinspection deprecation
            result = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        } else {
            //noinspection deprecation
            audio.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int change) {
        AudioLog.add("Rádio: " + AudioLog.focusName(change));
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            stopPlayback(false); // outra app (ex.: Spotify) começou a tocar
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && player != null) {
            player.setVolume(0.3f, 0.3f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && player != null) {
            player.setVolume(1f, 1f);
        }
    }

    // ---------- Sessão de multimédia e notificação ----------

    private void updateSession() {
        if (session == null || currentIndex < 0) return;
        RadioStations.Station station = RadioStations.ALL[currentIndex];
        String status = state == State.LOADING ? "A ligar…"
                : state == State.ERROR ? "Sem ligação. Verifique a internet." : "Rádio online · em direto";
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, station.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, status)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, stationArt(station))
                .build());
        int playbackState = state == State.PLAYING ? PlaybackState.STATE_PLAYING
                : state == State.LOADING ? PlaybackState.STATE_BUFFERING
                : state == State.ERROR ? PlaybackState.STATE_ERROR : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    private void startForegroundNow() {
        RadioStations.Station station = RadioStations.ALL[currentIndex];
        Intent open = new Intent(this, RadioActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_radio)
                .setContentTitle(station.name)
                .setContentText("Rádio online")
                .setContentIntent(content)
                .setOngoing(true)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private Bitmap stationArt(RadioStations.Station station) {
        return RadioStations.art(this, currentIndex);
    }
}
