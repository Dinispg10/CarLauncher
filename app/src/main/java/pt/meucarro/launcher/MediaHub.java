package pt.meucarro.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Sabe o que está a tocar no rádio (Bluetooth do telemóvel, Spotify, rádio...)
 * através das "sessões de multimédia" do Android, e controla essa música.
 */
public final class MediaHub implements MediaSessionManager.OnActiveSessionsChangedListener {

    public interface Listener {
        void onMediaChanged();
    }

    private static MediaHub instance;

    public static MediaHub get(Context context) {
        if (instance == null) instance = new MediaHub(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final MediaSessionManager sessionManager;
    private final ComponentName listenerComponent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private MediaController controller;
    private boolean registered;
    /** App que tocou por último (ex.: Bluetooth ou a nossa rádio): é a que os botões controlam. */
    private String lastPlayingPkg;

    /** Segue TODAS as sessões (não só a escolhida), para saber quando qualquer uma começa a tocar. */
    private final class Watch extends MediaController.Callback {
        final MediaController c;
        boolean playing;

        Watch(MediaController c) {
            this.c = c;
            PlaybackState st = c.getPlaybackState();
            this.playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        }

        PlaybackState lastState;

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            checkTrackChange(this, state);
            lastState = state;
            boolean now = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            boolean started = now && !playing;
            if (now != playing) AudioLog.add(label(c) + ": " + (now ? "a tocar" : "em pausa"));
            playing = now;
            if (started) {
                lastPlayingPkg = c.getPackageName();
                onStartedPlaying(c);
            }
            choose();
            notifyListeners();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            logMetadata(c, metadata);
            if (c == controller) {
                metaAt = SystemClock.elapsedRealtime();
                if (stale && !String.valueOf(rawTitle()).equals(staleTitle)) {
                    AudioLog.add("Nome da música chegou (com atraso)");
                    stale = false;
                }
                notifyListeners();
            }
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    }

    // ---------- Nome da música desatualizado ----------
    /*
     * Alguns telemóveis (iPhone por Bluetooth) às vezes mudam de música sem enviar o nome da nova:
     * o rádio continuava a mostrar a anterior (título, artista, álbum e capa errados).
     * Se a música acabou (ou carregou-se em ⏭) e o nome não muda em 4 s, deixamos de mostrar
     * o nome antigo — mais vale "Música do telemóvel" do que a música errada.
     */
    private boolean stale;
    private long metaAt;
    private long nextPressedAt;
    private String lastLogged;
    private String staleTitle;

    private static long extrapolate(PlaybackState st) {
        if (st == null || st.getPosition() < 0) return -1;
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        return pos;
    }

    private void checkTrackChange(Watch w, PlaybackState state) {
        if (w.c != controller || state == null) return;
        long before = extrapolate(w.lastState);
        long now = state.getPosition();
        if (before < 0 || now < 0 || now > 6000 || before < now + 10000) return; // não voltou ao início
        MediaMetadata m = w.c.getMetadata();
        long dur = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        boolean ended = dur > 0 && before > dur - 20000;
        boolean skipped = SystemClock.elapsedRealtime() - nextPressedAt < 6000;
        if (!ended && !skipped) return; // ex.: ⏮ para recomeçar a mesma música
        final long changedAt = SystemClock.elapsedRealtime();
        AudioLog.add("Música mudou (" + (ended ? "acabou" : "⏭") + "), à espera do nome novo…");
        main.postDelayed(() -> {
            if (metaAt < changedAt - 1500 && w.c == controller) {
                stale = true;
                staleTitle = String.valueOf(rawTitle());
                AudioLog.add("O telemóvel NÃO enviou o nome da música nova: a esconder o nome antigo");
                notifyListeners();
            }
        }, 4000);
    }

    private void logMetadata(MediaController c, MediaMetadata m) {
        if (m == null) return;
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence a = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        CharSequence al = m.getText(MediaMetadata.METADATA_KEY_ALBUM);
        long d = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String line = label(c) + " enviou: " + t + " — " + a + " · " + al
                + (d > 0 ? String.format(java.util.Locale.ROOT, " (%d:%02d)", d / 60000, d / 1000 % 60) : "");
        if (line.equals(lastLogged)) return;
        lastLogged = line;
        AudioLog.add(line);
    }

    /** O nome que o telemóvel enviou já não é o da música que está a tocar. */
    public boolean isStale() {
        return stale && hasMedia();
    }

    private final java.util.Map<android.media.session.MediaSession.Token, Watch> watches = new java.util.HashMap<>();

    private MediaHub(Context context) {
        this.context = context;
        this.sessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.listenerComponent = new ComponentName(context, MediaListener.class);
    }

    /** O utilizador já deu "acesso a notificações" a esta app? */
    public boolean hasAccess() {
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(context.getPackageName());
    }

    /** Abre o ecrã do Android onde se dá o "acesso a notificações". */
    public static void openAccessSettings(Context context) {
        try {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Toast.makeText(context, "Ative o \"Launcher do Carro\" nesta lista", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(context, "Abra: Definições → Aplicações e notificações → Avançadas → "
                    + "Acesso especial → Acesso a notificações", Toast.LENGTH_LONG).show();
        }
    }

    public void start() {
        if (!hasAccess() || sessionManager == null) return;
        if (!registered) {
            try {
                sessionManager.addOnActiveSessionsChangedListener(this, listenerComponent, main);
                registered = true;
            } catch (SecurityException e) {
                return;
            }
        }
        refresh();
    }

    public void refresh() {
        if (sessionManager == null) return;
        try {
            onActiveSessionsChanged(sessionManager.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            registered = false;
            setController(null);
        }
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> sessions) {
        // Atualiza a lista de sessões seguidas
        java.util.Set<android.media.session.MediaSession.Token> alive = new java.util.HashSet<>();
        if (sessions != null) {
            for (MediaController c : sessions) {
                // A rádio online tem o seu próprio ecrã; aqui só entra a música do telemóvel/outras apps
                if (context.getPackageName().equals(c.getPackageName())) continue;
                alive.add(c.getSessionToken());
                if (!watches.containsKey(c.getSessionToken())) {
                    Watch w = new Watch(c);
                    c.registerCallback(w, main);
                    watches.put(c.getSessionToken(), w);
                    if (w.playing && lastPlayingPkg == null) lastPlayingPkg = c.getPackageName();
                }
            }
        }
        java.util.Iterator<java.util.Map.Entry<android.media.session.MediaSession.Token, Watch>> it =
                watches.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<android.media.session.MediaSession.Token, Watch> e = it.next();
            if (!alive.contains(e.getKey())) {
                e.getValue().c.unregisterCallback(e.getValue());
                it.remove();
            }
        }
        choose();
    }

    /**
     * Escolhe a sessão que o ecrã mostra e que os botões controlam:
     * a que está a tocar; se nenhuma, a que tocou por último (e não simplesmente a primeira da lista).
     */
    private void choose() {
        MediaController best = null;
        MediaController lastUsed = null;
        MediaController any = null;
        for (Watch w : watches.values()) {
            String pkg = w.c.getPackageName();
            if (w.playing && (best == null || pkg.equals(lastPlayingPkg))) best = w.c;
            if (pkg.equals(lastPlayingPkg)) lastUsed = w.c;
            if (any == null) any = w.c;
        }
        if (best == null) best = lastUsed != null ? lastUsed : any;
        setController(best);
    }

    /** Só pode tocar uma coisa de cada vez: rádio online OU música do telemóvel. */
    private void onStartedPlaying(MediaController started) {
        if (!context.getPackageName().equals(started.getPackageName())) {
            if (RadioService.isOn()) AudioLog.add(label(started) + " começou: a parar a rádio");
            RadioService.State st = RadioService.state();
            if (st == RadioService.State.PLAYING || st == RadioService.State.LOADING) {
                try {
                    RadioService.send(context, RadioService.ACTION_STOP, -1);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Põe em pausa tudo o que não seja a nossa rádio (ex.: o Bluetooth quando a rádio começa). */
    public void pauseOthers() {
        List<MediaController> sessions;
        try {
            sessions = sessionManager.getActiveSessions(listenerComponent);
        } catch (Exception e) {
            return;
        }
        for (MediaController c : sessions) {
            if (context.getPackageName().equals(c.getPackageName())) continue;
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                AudioLog.add("Rádio começou: pausa em " + label(c));
                try {
                    c.getTransportControls().pause();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void setController(MediaController next) {
        if (controller != null && next != null
                && controller.getSessionToken().equals(next.getSessionToken())) {
            return;
        }
        controller = next;
        stale = false;
        metaAt = SystemClock.elapsedRealtime();
        notifyListeners();
    }

    // ---------- Ouvintes ----------

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        updateArt();
        for (Listener l : new ArrayList<>(listeners)) l.onMediaChanged();
    }

    // ---------- Capa atual (partilhada por todos os ecrãs) ----------

    private String artKey;
    private Bitmap artBitmap;
    private Integer artColor;

    /** Decide a capa da música atual: a do Bluetooth se vier, senão vai buscá-la à internet. */
    private void updateArt() {
        if (!hasMedia()) {
            artKey = null;
            artBitmap = null;
            artColor = null;
            return;
        }
        if (stale) {
            artKey = "stale";
            artBitmap = null;
            artColor = null;
            return;
        }
        Bitmap fromSession = art();
        String key = ArtFetcher.key(artist(), title());
        if (key.equals(artKey) && (artBitmap != null || fromSession == null)) return;
        artKey = key;
        if (fromSession != null) {
            setArt(fromSession);
            return;
        }
        artBitmap = null;
        artColor = null;
        final String requested = key;
        ArtFetcher.fetch(context, artist(), title(), (k, bitmap) -> {
            if (bitmap != null && requested.equals(artKey) && artBitmap != bitmap) {
                setArt(bitmap);
                for (Listener l : new ArrayList<>(listeners)) l.onMediaChanged();
            }
        });
    }

    private void setArt(Bitmap bitmap) {
        artBitmap = bitmap;
        artColor = ArtColors.accentFrom(bitmap);
    }

    /** Se a capa falhou (ex.: sem internet), tenta outra vez. */
    public void retryArtIfMissing() {
        if (hasMedia() && artBitmap == null) {
            ArtFetcher.forgetFailures();
            artKey = null;
            notifyListeners();
        }
    }

    public Bitmap currentArt() {
        return artBitmap;
    }

    /** Cor tirada da capa atual, ou null. */
    public Integer currentArtColor() {
        return artColor;
    }

    // ---------- Informação ----------

    public boolean hasMedia() {
        return controller != null && controller.getMetadata() != null && rawTitle() != null;
    }

    private String text(String key) {
        if (controller == null) return null;
        MediaMetadata m = controller.getMetadata();
        if (m == null) return null;
        CharSequence value = m.getText(key);
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public String title() {
        return stale ? "Música do telemóvel" : rawTitle();
    }

    private String rawTitle() {
        String t = text(MediaMetadata.METADATA_KEY_TITLE);
        return t != null ? t : text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    }

    public String artist() {
        if (stale) return "O telemóvel não enviou o nome desta música";
        String a = text(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null) a = text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        return a != null ? a : text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
    }

    public String album() {
        if (stale) return null;
        return text(MediaMetadata.METADATA_KEY_ALBUM);
    }

    /** Capa enviada pela própria sessão (no Bluetooth do seu rádio normalmente não vem). */
    public Bitmap art() {
        if (stale) return null;
        if (controller == null || controller.getMetadata() == null) return null;
        MediaMetadata m = controller.getMetadata();
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    public long durationMs() {
        if (controller == null || controller.getMetadata() == null) return 0;
        return controller.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    /** Posição atual, calculada a partir da última atualização recebida. */
    public long positionMs() {
        if (controller == null) return 0;
        PlaybackState state = controller.getPlaybackState();
        if (state == null) return 0;
        long pos = state.getPosition();
        if (pos < 0) return 0;
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            pos += (long) (elapsed * state.getPlaybackSpeed());
        }
        long dur = durationMs();
        return dur > 0 ? Math.min(pos, dur) : pos;
    }

    public boolean isPlaying() {
        if (controller == null) return false;
        PlaybackState state = controller.getPlaybackState();
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    /** Pacote da app que está a tocar, ou null. */
    public String sourcePackage() {
        return controller != null ? controller.getPackageName() : null;
    }

    /** Nome da app que está a tocar (ex.: "Bluetooth"). */
    public String sourceLabel() {
        if (controller == null) return null;
        if (context.getPackageName().equals(controller.getPackageName())) return "Rádio online";
        try {
            PackageManager pm = context.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(controller.getPackageName(), 0)));
        } catch (Exception e) {
            return controller.getPackageName();
        }
    }

    /** Lista das sessões ativas, para o diagnóstico nas definições. */
    public List<String> describeSessions() {
        List<String> out = new ArrayList<>();
        if (!hasAccess()) {
            out.add("Sem acesso a notificações.");
            return out;
        }
        try {
            List<MediaController> sessions = sessionManager.getActiveSessions(listenerComponent);
            for (MediaController c : sessions) {
                MediaMetadata m = c.getMetadata();
                String t = m != null && m.getText(MediaMetadata.METADATA_KEY_TITLE) != null
                        ? m.getText(MediaMetadata.METADATA_KEY_TITLE).toString() : "(sem título)";
                PlaybackState s = c.getPlaybackState();
                String st = s == null ? "?" : (s.getState() == PlaybackState.STATE_PLAYING ? "a tocar" : "parado");
                out.add(c.getPackageName() + "\n   " + t + " · " + st);
            }
        } catch (Exception e) {
            out.add("Erro: " + e.getMessage());
        }
        if (out.isEmpty()) out.add("Nenhuma sessão de música ativa.");
        return out;
    }

    // ---------- Controlo ----------

    private String label(MediaController c) {
        String pkg = c.getPackageName();
        return pkg.contains("bluetooth") ? "Bluetooth" : pkg;
    }

    public void playPause() {
        AudioLog.add("Botão ⏯ (telemóvel): " + (controller == null ? "sem sessão, tecla multimédia"
                : isPlaying() ? "estava a tocar → pausa" : "estava em pausa → play"));
        if (controller != null) {
            if (isPlaying()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        } else {
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    public void next() {
        nextPressedAt = SystemClock.elapsedRealtime();
        if (controller != null) controller.getTransportControls().skipToNext();
        else sendKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
        else sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    public void seekTo(long ms) {
        if (controller != null) controller.getTransportControls().seekTo(ms);
    }

    private void sendKey(int keyCode) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }
}
