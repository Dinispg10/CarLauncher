package pt.meucarro.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Ecrã "Rádio a tocar": logótipo grande, barras animadas, controlos e fila para mudar de rádio. */
public class RadioNowActivity extends Activity implements RadioService.Listener {

    private View root;
    private ImageView logo, play;
    private TextView state, name, genre, song;
    private Object shownArt;
    private View dot;
    private EqualizerView eq;
    private final FrameLayout[] stripItems = new FrameLayout[RadioStations.ALL.length];
    private int shownIndex = -1;
    private int accent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int index = Math.max(0, RadioService.currentIndex());
        accent = RadioStations.ALL[index].color;

        ScaleFrame frame = new ScaleFrame(this);
        getLayoutInflater().inflate(R.layout.layout_radio_now, frame, true);
        setContentView(frame);
        Ui.addClock(this);
        root = frame;

        logo = findViewById(R.id.now_logo);
        play = findViewById(R.id.now_play);
        state = findViewById(R.id.now_state);
        name = findViewById(R.id.now_name);
        genre = findViewById(R.id.now_genre);
        song = findViewById(R.id.now_song);
        dot = findViewById(R.id.now_dot);
        eq = findViewById(R.id.now_eq);

        GradientDrawable round = new GradientDrawable();
        round.setCornerRadius(32f);
        round.setColor(0xFF13161B);
        logo.setBackground(round);
        logo.setClipToOutline(true);

        buildStrip(findViewById(R.id.now_strip));
        Ui.style(frame, accent);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_all).setOnClickListener(v -> {
            startActivity(new Intent(this, RadioActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        findViewById(R.id.now_prev).setOnClickListener(v -> RadioService.send(this, RadioService.ACTION_PREV, -1));
        findViewById(R.id.now_next).setOnClickListener(v -> RadioService.send(this, RadioService.ACTION_NEXT, -1));
        play.setOnClickListener(v -> {
            if (RadioService.isOn()) RadioService.send(this, RadioService.ACTION_STOP, -1);
            else RadioService.send(this, RadioService.ACTION_PLAY, Math.max(0, RadioService.currentIndex()));
        });
    }

    private void buildStrip(LinearLayout strip) {
        for (int i = 0; i < RadioStations.ALL.length; i++) {
            final int index = i;
            FrameLayout ring = new FrameLayout(this);
            ring.setPadding(4, 4, 4, 4);
            ring.setClickable(true);
            ring.setContentDescription("Tocar " + RadioStations.ALL[i].name);
            ring.setOnClickListener(v -> RadioService.send(this, RadioService.ACTION_PLAY, index));

            ImageView img = new ImageView(this);
            img.setImageBitmap(RadioStations.art(this, i));
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable r = new GradientDrawable();
            r.setCornerRadius(14f);
            r.setColor(0xFF13161B);
            img.setBackground(r);
            img.setClipToOutline(true);
            ring.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(72, 72);
            if (i > 0) lp.leftMargin = 18;
            strip.addView(ring, lp);
            stripItems[i] = ring;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
        Night.apply(this, Night.isNight(this));
        RadioService.addListener(this);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        RadioService.removeListener(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.applySystemBars(this);
    }

    @Override
    public void onRadioChanged() {
        render();
    }

    private void render() {
        int index = Math.max(0, RadioService.currentIndex());
        RadioStations.Station station = RadioStations.ALL[index];
        RadioService.State st = RadioService.state();

        if (index != shownIndex) {
            shownIndex = index;
            logo.setImageBitmap(RadioStations.art(this, index));
            name.setText(station.name);
            genre.setText(station.genre);
            // A cor do ecrã passa suavemente para a cor da rádio
            Ui.animateAccent(root, accent, station.color);
            accent = station.color;
            eq.setColor(station.color);
            logo.setAlpha(0f);
            logo.setScaleX(0.94f);
            logo.setScaleY(0.94f);
            logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start();
        }

        // Música a tocar (quando a rádio a envia) e a capa no lugar do logótipo
        boolean hasSong = st == RadioService.State.PLAYING && RadioMeta.hasSong(index);
        song.setVisibility(hasSong ? View.VISIBLE : View.GONE);
        if (hasSong) song.setText("♪  " + RadioMeta.songLine());
        Object wantArt = hasSong && RadioMeta.art != null ? RadioMeta.art : (Object) index;
        if (!wantArt.equals(shownArt)) {
            shownArt = wantArt;
            logo.setImageBitmap(wantArt instanceof Integer ? RadioStations.art(this, index) : RadioMeta.art);
            logo.setAlpha(0f);
            logo.animate().alpha(1f).setDuration(400).start();
        }

        switch (st) {
            case PLAYING:
                state.setText("AO VIVO");
                eq.setMode(EqualizerView.Mode.PLAYING);
                break;
            case LOADING:
                state.setText("A LIGAR…");
                eq.setMode(EqualizerView.Mode.LOADING);
                break;
            case ERROR:
                state.setText("SEM LIGAÇÃO · VERIFIQUE A INTERNET");
                eq.setMode(EqualizerView.Mode.STOPPED);
                break;
            default:
                state.setText("PARADA");
                eq.setMode(EqualizerView.Mode.STOPPED);
                break;
        }
        play.setImageResource(RadioService.isOn() ? R.drawable.ic_pause_dark : R.drawable.ic_play_dark);

        // Ponto "ao vivo" a piscar só quando está a tocar
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(st == RadioService.State.PLAYING ? 0xFFFF4D4D : 0xFF5B616B);
        dot.setBackground(dotShape);
        dot.animate().cancel();
        if (st == RadioService.State.PLAYING) pulse();
        else dot.setAlpha(1f);

        // Rádio atual marcada na fila de baixo
        for (int i = 0; i < stripItems.length; i++) {
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(18f);
            if (i == index) ring.setStroke(3, station.color);
            stripItems[i].setBackground(ring);
            stripItems[i].setAlpha(i == index ? 1f : 0.55f);
        }
    }

    private void pulse() {
        dot.animate().alpha(0.25f).setDuration(700).withEndAction(() ->
                dot.animate().alpha(1f).setDuration(700).withEndAction(() -> {
                    if (RadioService.state() == RadioService.State.PLAYING && !isFinishing()) pulse();
                }).start()).start();
    }
}
