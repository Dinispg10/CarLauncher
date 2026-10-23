package pt.meucarro.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/** Ecrã "A tocar": capa, música, artista, progresso e controlos. */
public class NowPlayingActivity extends Activity implements MediaHub.Listener {

    private MediaHub hub;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View root;
    private int currentAccent;
    private int ticks;

    private TextView label, title, subtitle, chip, timePos, timeDur, access;
    private ImageView art, artPlaceholder, play;
    private View progressRow, progressTrack, progressFill, progressThumb;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (++ticks % 120 == 0) Night.apply(NowPlayingActivity.this, Night.isNight(NowPlayingActivity.this));
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hub = MediaHub.get(this);
        int accent = Ui.effectiveAccent(this, hub);
        currentAccent = accent;

        ScaleFrame frame = new ScaleFrame(this);
        getLayoutInflater().inflate(R.layout.layout_tocar, frame, true);
        setContentView(frame);
        Ui.style(frame, accent);
        root = frame;

        label = findViewById(R.id.np_label);
        title = findViewById(R.id.np_title);
        subtitle = findViewById(R.id.np_subtitle);
        chip = findViewById(R.id.source_chip);
        timePos = findViewById(R.id.time_pos);
        timeDur = findViewById(R.id.time_dur);
        access = findViewById(R.id.btn_access);
        art = findViewById(R.id.art);
        artPlaceholder = findViewById(R.id.art_placeholder);
        play = findViewById(R.id.np_play);
        progressRow = findViewById(R.id.progress_row);
        progressTrack = findViewById(R.id.progress_track);
        progressFill = findViewById(R.id.progress_fill);
        progressThumb = findViewById(R.id.progress_thumb);

        findViewById(R.id.art_box).setClipToOutline(true);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.np_prev).setOnClickListener(v -> hub.previous());
        findViewById(R.id.np_next).setOnClickListener(v -> hub.next());
        play.setOnClickListener(v -> hub.playPause());
        access.setOnClickListener(v -> MediaHub.openAccessSettings(this));

        // A etiqueta do canto abre a app que está a tocar (ex.: Bluetooth, para gerir os telemóveis)
        chip.setOnClickListener(v -> openSourceApp());

        // Tocar na barra para saltar (nem todos os telemóveis aceitam isto por Bluetooth)
        progressTrack.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && v.getWidth() > 0) {
                long dur = hub.durationMs();
                if (dur > 0) {
                    float f = Math.max(0f, Math.min(1f, event.getX() / v.getWidth()));
                    hub.seekTo((long) (f * dur));
                }
                v.performClick();
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
        hub.addListener(this);
        hub.start();
        hub.retryArtIfMissing();
        Night.apply(this, Night.isNight(this));
        render();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        hub.removeListener(this);
        handler.removeCallbacks(ticker);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.applySystemBars(this);
    }

    @Override
    public void onMediaChanged() {
        render();
    }

    private void openSourceApp() {
        if (getPackageName().equals(hub.sourcePackage())) {
            startActivity(new android.content.Intent(this, RadioNowActivity.class));
            return;
        }
        // Música por Bluetooth (ou nada a tocar): abre a app de Bluetooth do rádio,
        // a mesma do botão Telefone, onde se gerem os telemóveis
        String source = hub.sourcePackage();
        boolean bluetooth = source == null || source.contains("bluetooth");
        if (!bluetooth && AppUtils.launch(this, source, null)) return;
        android.content.SharedPreferences prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        if (!AppUtils.launch(this, prefs.getString("slot_phone_pkg", null), prefs.getString("slot_phone_cls", null))) {
            android.widget.Toast.makeText(this,
                    "Escolha a app de Bluetooth com um toque longo no botão Telefone do ecrã inicial",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void showChip(String text) {
        chip.setVisibility(View.VISIBLE);
        chip.setText(text + "  ›");
    }

    private void render() {
        if (!hub.hasAccess()) {
            label.setText("MULTIMÉDIA");
            title.setText("Falta uma autorização");
            subtitle.setText("Dê \"acesso a notificações\" ao Launcher do Carro para ver a música.");
            subtitle.setMaxLines(3);
            access.setVisibility(View.VISIBLE);
            chip.setVisibility(View.GONE);
            progressRow.setVisibility(View.GONE);
            showArt(null);
            updateAccent();
            return;
        }
        access.setVisibility(View.GONE);
        subtitle.setMaxLines(1);

        if (!hub.hasMedia()) {
            label.setText("MULTIMÉDIA");
            title.setText("Nada a tocar");
            subtitle.setText("Ponha música no telemóvel");
            showChip("Telemóveis");
            progressRow.setVisibility(View.GONE);
            play.setImageResource(R.drawable.ic_play_dark);
            showArt(null);
            updateAccent();
            return;
        }

        String songTitle = hub.title();
        String artist = hub.artist();
        String album = hub.album();
        boolean playing = hub.isPlaying();

        label.setText(playing ? "A TOCAR" : "EM PAUSA");
        title.setText(songTitle);
        StringBuilder sub = new StringBuilder();
        if (artist != null) sub.append(artist);
        if (album != null && !album.equalsIgnoreCase(songTitle)) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(album);
        }
        subtitle.setText(sub.length() > 0 ? sub.toString() : " ");
        play.setImageResource(playing ? R.drawable.ic_pause_dark : R.drawable.ic_play_dark);

        String source = hub.sourceLabel();
        String pkg = hub.sourcePackage();
        if (pkg != null && pkg.contains("bluetooth")) showChip("Telemóveis");
        else showChip(source != null ? source : "Telemóveis");

        showArt(hub.currentArt());
        updateAccent();
        updateProgress();
    }

    /** A cor de destaque acompanha a capa (se essa opção estiver ligada). */
    private void updateAccent() {
        int next = Ui.effectiveAccent(this, hub);
        if (next != currentAccent) {
            Ui.animateAccent(root, currentAccent, next);
            currentAccent = next;
        }
    }

    private void showArt(Bitmap bitmap) {
        if (bitmap != null) {
            art.setImageBitmap(bitmap);
            art.setVisibility(View.VISIBLE);
            artPlaceholder.setVisibility(View.GONE);
        } else {
            art.setImageDrawable(null);
            art.setVisibility(View.GONE);
            artPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private void updateProgress() {
        if (!hub.hasMedia()) return;
        long dur = hub.durationMs();
        if (dur <= 0) {
            progressRow.setVisibility(View.GONE);
            return;
        }
        progressRow.setVisibility(View.VISIBLE);
        long pos = hub.positionMs();
        int width = progressTrack.getWidth();
        if (width > 0) {
            float f = Math.max(0f, Math.min(1f, pos / (float) dur));
            int fill = Math.round(width * f);
            ViewGroup.LayoutParams lp = progressFill.getLayoutParams();
            if (lp.width != fill) {
                lp.width = fill;
                progressFill.setLayoutParams(lp);
            }
            int thumb = progressThumb.getWidth();
            float x = Math.max(0, Math.min(width - thumb, fill - thumb / 2f));
            progressThumb.setTranslationX(x);
        }
        timePos.setText(format(pos));
        timeDur.setText(format(dur));
    }

    private static String format(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
