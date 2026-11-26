package pt.meucarro.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Toca as músicas guardadas no rádio. Publica uma sessão de multimédia ("Offline"), por isso aparece
 * no ecrã "A tocar" como a música do telemóvel (capa, barra, letra, botões do volante).
 * Lembra-se da música e do sítio onde ficou.
 */
public class OfflineService extends Service implements AudioManager.OnAudioFocusChangeListener {

    public static final String SESSION_TAG = "Offline";
    private static final String ACTION = "pt.meucarro.launcher.offline.";
    private static final int NOTIFICATION_ID = 43;
    private static final String CHANNEL = "offline";

    private static OfflineService running;
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private List<String> queue = new ArrayList<>();
    private int index;
    private boolean playing, prepared, shuffle;
    private Bitmap art;

    // ---------- Para os ecrãs ----------

    /** Toca uma lista de músicas a partir de uma delas. */
    public static void play(Context c, List<OfflineMusic.Track> tracks, int start, boolean shuffle) {
        ArrayList<String> paths = new ArrayList<>();
        for (OfflineMusic.Track t : tracks) paths.add(t.path);
        Intent i = new Intent(c, OfflineService.class).setAction(ACTION + "PLAY")
                .putStringArrayListExtra("queue", paths).putExtra("index", start).putExtra("shuffle", shuffle);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    /** Continua a última música (onde tinha ficado). */
    public static void resume(Context c) {
        Intent i = new Intent(c, OfflineService.class).setAction(ACTION + "RESUME");
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void pause() {
        OfflineService s = running;
        if (s != null && s.playing) s.pausePlayback();
    }

    public static boolean isRunning() {
        return running != null;
    }

    /** Desliga de vez (larga o som para o telemóvel). O "Continuar" continua a lembrar-se do sítio. */
    public static void stop(Context c) {
        OfflineService s = running;
        if (s == null) return;
        s.pausePlayback();
        s.stopForeground(true);
        s.stopSelf();
    }

    public static boolean isPlaying() {
        OfflineService s = running;
        return s != null && s.playing;
    }

    /** Caminho da música atual (ou null). */
    public static String currentPath() {
        OfflineService s = running;
        return s == null || s.queue.isEmpty() ? null : s.queue.get(s.index);
    }

    /** Esta sessão de multimédia é a das músicas offline? (a rádio online também é nossa) */
    public static boolean isOurSession(MediaSession.Token t) {
        OfflineService s = running;
        return s != null && s.session != null && s.session.getSessionToken().equals(t);
    }

    public static boolean hasResume(Context c) {
        return prefs(c).getString("offline_last", null) != null;
    }

    public static void addListener(Runnable r) {
        if (!LISTENERS.contains(r)) LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("launcher", MODE_PRIVATE);
    }

    // ---------- Serviço ----------

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, SESSION_TAG);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                resumePlayback();
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onStop() {
                pausePlayback();
            }

            @Override
            public void onSkipToNext() {
                next(true);
            }

            @Override
            public void onSkipToPrevious() {
                // Como nos leitores normais: a meio da música, volta ao início dela
                if (player != null && prepared && player.getCurrentPosition() > 4000) {
                    player.seekTo(0);
                    updateState();
                } else {
                    open((index - 1 + queue.size()) % Math.max(1, queue.size()), 0, true);
                }
            }

            @Override
            public void onSeekTo(long pos) {
                if (player != null && prepared) {
                    player.seekTo((int) pos);
                    updateState();
                }
            }
        });
        //noinspection deprecation
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Músicas offline", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNow("Músicas offline");
        String a = intent == null ? null : intent.getAction();
        if ((ACTION + "PLAY").equals(a)) {
            List<String> q = intent.getStringArrayListExtra("queue");
            if (q != null && !q.isEmpty()) {
                int start = Math.max(0, Math.min(q.size() - 1, intent.getIntExtra("index", 0)));
                shuffle = intent.getBooleanExtra("shuffle", false);
                if (shuffle) {
                    String first = q.get(start);
                    q = new ArrayList<>(q);
                    Collections.shuffle(q);
                    q.remove(first);
                    q.add(0, first);
                    start = 0;
                }
                queue = new ArrayList<>(q);
                open(start, 0, true);
            }
        } else if ((ACTION + "RESUME").equals(a)) {
            if (player != null && prepared) resumePlayback();
            else restore();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        saveState();
        release();
        abandonFocus();
        session.setActive(false);
        session.release();
        if (running == this) running = null;
        notifyListeners();
        super.onDestroy();
    }

    // ---------- Tocar ----------

    private void open(int i, int positionMs, boolean start) {
        if (queue.isEmpty()) return;
        index = i;
        release();
        String path = queue.get(index);
        OfflineMusic.Track t = OfflineMusic.find(this, path);
        AudioLog.add("Offline: " + (t != null ? t.title : path));
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        final MediaPlayer mp = player;
        player.setOnPreparedListener(p -> {
            if (p != player) return;
            prepared = true;
            if (positionMs > 0) p.seekTo(positionMs);
            if (start) resumePlayback();
            else updateState();
        });
        player.setOnCompletionListener(p -> {
            if (p == player) next(false);
        });
        player.setOnErrorListener((p, what, extra) -> {
            AudioLog.add("Offline: não consegui tocar (" + what + "/" + extra + ")");
            if (p == player) main.postDelayed(() -> next(false), 500);
            return true;
        });
        art = null;
        updateMetadata(t);
        // Capa: lida em segundo plano (pode demorar um bocadinho)
        new Thread(() -> {
            Bitmap b = OfflineMusic.art(this, path, 600);
            main.post(() -> {
                if (mp == player) {
                    art = b;
                    updateMetadata(t);
                }
            });
        }).start();
        try {
            player.setDataSource(path);
            player.prepareAsync();
        } catch (Exception e) {
            AudioLog.add("Offline: ficheiro inválido");
            main.postDelayed(() -> next(false), 500);
        }
        prefs(this).edit().putString("offline_last", path).apply();
        notifyListeners();
    }

    private void next(boolean user) {
        if (queue.isEmpty()) return;
        if (!user && index == queue.size() - 1) {
            // Fim da lista: volta ao início
            open(0, 0, true);
            return;
        }
        open((index + 1) % queue.size(), 0, true);
    }

    private void resumePlayback() {
        if (player == null || !prepared) {
            restore();
            return;
        }
        if (!requestFocus()) return;
        RadioService.State st = RadioService.state();
        if (st == RadioService.State.PLAYING || st == RadioService.State.LOADING) {
            RadioService.send(this, RadioService.ACTION_STOP, -1);
        }
        MediaHub.get(this).pauseOthers();
        session.setActive(true);
        player.start();
        playing = true;
        startForegroundNow(currentTitle());
        updateState();
        notifyListeners();
    }

    private void pausePlayback() {
        if (player != null && prepared && player.isPlaying()) player.pause();
        playing = false;
        saveState();
        updateState();
        notifyListeners();
    }

    /** Abre a última música no sítio onde ficou. */
    private void restore() {
        SharedPreferences p = prefs(this);
        String last = p.getString("offline_last", null);
        List<OfflineMusic.Track> all = OfflineMusic.allTracks(this);
        if (all.isEmpty()) {
            stopSelf();
            return;
        }
        queue = new ArrayList<>();
        int start = 0;
        // A fila é o álbum da última música
        OfflineMusic.Track lastT = last == null ? null : OfflineMusic.find(this, last);
        for (OfflineMusic.Album a : OfflineMusic.albums(this)) {
            if (lastT != null && a.tracks.contains(lastT)) {
                for (OfflineMusic.Track t : a.tracks) queue.add(t.path);
                start = queue.indexOf(last);
            }
        }
        if (queue.isEmpty()) for (OfflineMusic.Track t : all) queue.add(t.path);
        open(Math.max(0, start), lastT != null ? p.getInt("offline_pos", 0) : 0, true);
    }

    private void saveState() {
        if (player != null && prepared) {
            try {
                prefs(this).edit().putInt("offline_pos", player.getCurrentPosition()).apply();
            } catch (Exception ignored) {
            }
        }
    }

    private void release() {
        prepared = false;
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private String currentTitle() {
        OfflineMusic.Track t = queue.isEmpty() ? null : OfflineMusic.find(this, queue.get(index));
        return t != null ? t.title : "Músicas offline";
    }

    // ---------- Sessão ----------

    private void updateMetadata(OfflineMusic.Track t) {
        MediaMetadata.Builder b = new MediaMetadata.Builder();
        if (t != null) {
            b.putString(MediaMetadata.METADATA_KEY_TITLE, t.title);
            b.putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist != null ? t.artist : "Músicas offline");
            if (t.album != null) b.putString(MediaMetadata.METADATA_KEY_ALBUM, t.album);
            b.putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs);
        }
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(b.build());
        updateState();
    }

    private void updateState() {
        long pos = 0;
        try {
            if (player != null && prepared) pos = player.getCurrentPosition();
        } catch (Exception ignored) {
        }
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f,
                        SystemClock.elapsedRealtime())
                .build());
    }

    private static void notifyListeners() {
        for (Runnable r : new ArrayList<>(LISTENERS)) r.run();
    }

    // ---------- Foco de áudio ----------

    private boolean requestFocus() {
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(this).build();
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
        AudioLog.add("Offline: " + AudioLog.focusName(change));
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pausePlayback(); // chamada, GPS a falar, outra app...
            if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pausedByFocus = true;
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && player != null) {
            player.setVolume(0.3f, 0.3f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (player != null) player.setVolume(1f, 1f);
            if (pausedByFocus) {
                pausedByFocus = false;
                resumePlayback();
            }
        }
    }

    private boolean pausedByFocus;

    private void startForegroundNow(String title) {
        Intent open = new Intent(this, OfflineActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_radio)
                .setContentTitle(title)
                .setContentText("Músicas offline")
                .setContentIntent(content)
                .setOngoing(true)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                .build();
        startForeground(NOTIFICATION_ID, n);
    }
}
