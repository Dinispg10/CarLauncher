package pt.meucarro.launcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Ecrã Rádio: 8 rádios portuguesas em botões grandes, um toque para tocar. */
public class RadioActivity extends Activity implements RadioService.Listener {

    private static final int COLUMNS = 5; // 9 rádios em 2 filas

    private int accent;
    private final View[] tiles = new View[RadioStations.ALL.length];
    private final TextView[] tileStatus = new TextView[RadioStations.ALL.length];
    private TextView statusView, nameView;
    private ImageView playButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        accent = prefs.getInt("accent", 0xFF5EEAD4);

        ScaleFrame frame = new ScaleFrame(this);
        getLayoutInflater().inflate(R.layout.layout_radio, frame, true);
        setContentView(frame);

        buildGrid(findViewById(R.id.radio_grid));
        Ui.style(frame, accent);

        statusView = findViewById(R.id.radio_status);
        nameView = findViewById(R.id.radio_name);
        // Tocar no nome da rádio (barra de baixo) abre o ecrã "Rádio a tocar"
        ((View) nameView.getParent()).setOnClickListener(v -> {
            if (RadioService.currentIndex() >= 0) {
                startActivity(new android.content.Intent(this, RadioNowActivity.class));
            }
        });
        playButton = findViewById(R.id.radio_play);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.radio_play).setOnClickListener(v ->
                RadioService.send(this, RadioService.ACTION_TOGGLE, -1));
        findViewById(R.id.radio_next).setOnClickListener(v ->
                RadioService.send(this, RadioService.ACTION_NEXT, -1));
        findViewById(R.id.radio_prev).setOnClickListener(v ->
                RadioService.send(this, RadioService.ACTION_PREV, -1));
    }

    private void buildGrid(LinearLayout grid) {
        int rows = (RadioStations.ALL.length + COLUMNS - 1) / COLUMNS;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (r > 0) rowLp.topMargin = 16;
            grid.addView(row, rowLp);
            for (int c = 0; c < COLUMNS; c++) {
                int index = r * COLUMNS + c;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (c > 0) lp.leftMargin = 16;
                if (index >= RadioStations.ALL.length) {
                    row.addView(new View(this), lp);
                    continue;
                }
                row.addView(buildTile(index), lp);
            }
        }
    }

    private View buildTile(int index) {
        RadioStations.Station station = RadioStations.ALL[index];

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(22, 18, 18, 18);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription("Tocar " + station.name);
        tile.setOnClickListener(v -> {
            // Toca a rádio (se ainda não for esta) e abre o ecrã "Rádio a tocar"
            if (!(RadioService.currentIndex() == index && RadioService.isOn())) {
                RadioService.send(this, RadioService.ACTION_PLAY, index);
            }
            startActivity(new android.content.Intent(this, RadioNowActivity.class));
        });

        // Linha de cima: ponto com a cor da rádio + estado
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(station.color);
        dot.setBackground(dotShape);
        top.addView(dot, new LinearLayout.LayoutParams(16, 16));
        TextView status = new TextView(this);
        status.setTag("w700");
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13);
        status.setLetterSpacing(0.12f);
        status.setGravity(Gravity.END);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(status, statusLp);
        tile.addView(top);

        boolean hasLogo = RadioStations.logoRes(this, station) != 0;
        if (hasLogo) {
            // Logótipo da rádio, com cantos redondos
            ImageView logo = new ImageView(this);
            logo.setImageBitmap(RadioStations.art(this, index));
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            GradientDrawable round = new GradientDrawable();
            round.setCornerRadius(16f);
            round.setColor(0x00000000);
            logo.setBackground(round);
            logo.setClipToOutline(true);
            LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            logoLp.topMargin = 6;
            logoLp.bottomMargin = 8;
            tile.addView(logo, logoLp);
        } else {
            View spacer = new View(this);
            tile.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        }

        TextView name = new TextView(this);
        name.setTag("w600");
        name.setText(station.name);
        name.setTextColor(0xFFF2F3F5);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, hasLogo ? 22 : 32);
        name.setMaxLines(1);
        tile.addView(name);

        TextView genre = new TextView(this);
        genre.setTag("w500");
        genre.setText(station.genre);
        genre.setTextColor(0xFF9AA0A6);
        genre.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16);
        genre.setMaxLines(1);
        if (!hasLogo) tile.addView(genre);

        tiles[index] = tile;
        tileStatus[index] = status;
        return tile;
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
        int current = RadioService.currentIndex();
        RadioService.State st = RadioService.state();
        for (int i = 0; i < tiles.length; i++) {
            boolean active = i == current && st != RadioService.State.STOPPED;
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius(24f);
            shape.setColor(active ? 0xFF181C22 : 0xFF13161B);
            int stationColor = RadioStations.ALL[i].color;
            shape.setStroke(active ? 3 : 1, active ? stationColor : 0xFF22262D);
            tiles[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));

            TextView status = tileStatus[i];
            if (!active) {
                status.setText("");
            } else if (st == RadioService.State.PLAYING) {
                status.setText("A TOCAR");
                status.setTextColor(stationColor);
            } else if (st == RadioService.State.LOADING) {
                status.setText("A LIGAR…");
                status.setTextColor(0xFF9AA0A6);
            } else {
                status.setText("SEM LIGAÇÃO");
                status.setTextColor(0xFFFF8A80);
            }
        }

        if (current < 0) {
            statusView.setText("RÁDIO ONLINE");
            nameView.setText("Escolha uma rádio");
        } else {
            String label = st == RadioService.State.PLAYING ? "A TOCAR"
                    : st == RadioService.State.LOADING ? "A LIGAR…"
                    : st == RadioService.State.ERROR ? "SEM LIGAÇÃO · VERIFIQUE A INTERNET" : "PARADA";
            statusView.setText(label);
            nameView.setText(RadioStations.ALL[current].name);
        }
        boolean on = st == RadioService.State.PLAYING || st == RadioService.State.LOADING;
        playButton.setImageResource(on ? R.drawable.ic_pause_dark : R.drawable.ic_play_dark);
    }
}
