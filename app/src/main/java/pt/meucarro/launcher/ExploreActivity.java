package pt.meucarro.launcher;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Ecrã "Mapa de exploração": todas as estradas por onde o Golf passou e os concelhos descobertos. */
public class ExploreActivity extends Activity implements Explore.Listener {

    private static final Locale PT = Locale.forLanguageTag("pt-PT");
    private int accent;
    private ExploreView map;
    private TextView kmValue, concValue, concPct, distValue, lastValue, tapLabel;
    private View concBar, concTrack;
    private long lastReload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accent = Ui.effectiveAccent(this, MediaHub.get(this));

        ScaleFrame frame = new ScaleFrame(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0D10);
        root.setPadding(32, 20, 32, 24);
        frame.addView(root);

        // Barra de cima
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout back = new LinearLayout(this);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setBackgroundResource(R.drawable.pill_bg);
        back.setPadding(16, 0, 22, 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_back);
        back.addView(icon, new LinearLayout.LayoutParams(22, 22));
        TextView bt = text("Carro", "600", 17, 0xFFF2F3F5);
        bt.setPadding(10, 0, 0, 0);
        back.addView(bt);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 52));
        TextView title = text("Exploração", "600", 30, 0xFFF2F3F5);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = 22;
        bar.addView(title, tlp);
        bar.addView(chip("As minhas estradas", v -> map.fitTrackOrPortugal()), chipLp(false));
        bar.addView(chip("Portugal", v -> map.fitPortugal()), chipLp(true));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56));

        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rlp.topMargin = 16;
        root.addView(row, rlp);

        // Mapa
        FrameLayout mapCard = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(28f);
        bg.setColor(0xFF0E1013);
        bg.setStroke(1, 0xFF22262D);
        mapCard.setBackground(bg);
        mapCard.setClipToOutline(true);
        map = new ExploreView(this);
        map.setAccent(accent);
        mapCard.addView(map, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        tapLabel = text("", "600", 17, 0xFFF2F3F5);
        tapLabel.setPadding(18, 10, 18, 10);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(18f);
        tb.setColor(0xE613161B);
        tb.setStroke(1, 0xFF2C313A);
        tapLabel.setBackground(tb);
        tapLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        llp.setMargins(16, 16, 16, 16);
        mapCard.addView(tapLabel, llp);
        map.setTapListener(k -> {
            if (k == null) {
                tapLabel.setVisibility(View.GONE);
                return;
            }
            boolean seen = Explore.visited(this).contains(k.key());
            tapLabel.setText(k.name + "  ·  " + k.district + (seen ? "   ✓ visitado" : "   por visitar"));
            tapLabel.setTextColor(seen ? accent : 0xFFC9CDD3);
            tapLabel.setVisibility(View.VISIBLE);
        });
        row.addView(mapCard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // Números
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(270, ViewGroup.LayoutParams.MATCH_PARENT);
        slp.leftMargin = 18;
        row.addView(side, slp);

        LinearLayout kmCard = card(side, "ESTRADAS DIFERENTES");
        kmValue = text("0 km", "600", 40, 0xFFF2F3F5);
        kmCard.addView(kmValue);

        LinearLayout concCard = card(side, "CONCELHOS");
        LinearLayout cv = new LinearLayout(this);
        cv.setGravity(Gravity.BOTTOM);
        concValue = text("0", "600", 40, 0xFFF2F3F5);
        cv.addView(concValue);
        TextView of = text(" / " + Concelhos.TOTAL, "500", 20, 0xFF9AA0A6);
        of.setPadding(0, 0, 0, 6);
        cv.addView(of);
        concPct = text("", "600", 17, accent);
        concPct.setPadding(12, 0, 0, 7);
        cv.addView(concPct);
        concCard.addView(cv);
        FrameLayout track = new FrameLayout(this);
        GradientDrawable trk = new GradientDrawable();
        trk.setCornerRadius(3f);
        trk.setColor(0xFF22262D);
        track.setBackground(trk);
        concBar = new View(this);
        GradientDrawable fil = new GradientDrawable();
        fil.setCornerRadius(3f);
        fil.setColor(accent);
        concBar.setBackground(fil);
        track.addView(concBar, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        concTrack = track;
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6);
        trlp.topMargin = 6;
        concCard.addView(track, trlp);
        concCard.setOnClickListener(v -> showList());

        LinearLayout distCard = card(side, "DISTRITOS");
        distValue = text("0 / 18", "600", 30, 0xFFF2F3F5);
        distCard.addView(distValue);
        distCard.setOnClickListener(v -> showList());

        LinearLayout lastCard = card(side, "ÚLTIMO DESCOBERTO");
        lastValue = text("—", "600", 26, 0xFFF2F3F5);
        lastCard.addView(lastValue);

        setContentView(frame);
        Ui.addClock(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
        Night.apply(this, Night.isNight(this));
        Explore.addListener(this);
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Explore.removeListener(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.applySystemBars(this);
    }

    @Override
    public void onExploreChanged() {
        // a conduzir com este ecrã aberto: atualiza no máximo de 20 em 20 segundos
        if (System.currentTimeMillis() - lastReload > 20000) reload();
        else renderStats();
    }

    @Override
    public void onNewConcelho(String name, String district, int count) {
        Celebrate.show(this, name, count + " de " + Concelhos.TOTAL + " concelhos  ·  " + district, accent);
        reload();
    }

    private void reload() {
        lastReload = System.currentTimeMillis();
        renderStats();
        new Thread(() -> {
            List<Concelhos.Concelho> list = Concelhos.all(this);
            runOnUiThread(() -> map.setConcelhos(list, Explore.visited(this)));
        }).start();
        Explore.loadTrack(this, (x, y) -> map.setTrack(x, y));
    }

    private void renderStats() {
        double km = Explore.uniqueKm(this);
        kmValue.setText(km < 10 ? String.format(PT, "%.1f km", km)
                : String.format(PT, "%,d km", Math.round(km)).replace(',', ' '));
        Set<String> seen = Explore.visited(this);
        int n = seen.size();
        concValue.setText(String.valueOf(n));
        concPct.setText(Math.round(n * 100.0 / Concelhos.TOTAL) + " %");
        concTrack.post(() -> {
            ViewGroup.LayoutParams lp = concBar.getLayoutParams();
            lp.width = (int) (concTrack.getWidth() * Math.min(1.0, n / (double) Concelhos.TOTAL));
            concBar.setLayoutParams(lp);
        });
        java.util.Set<String> districts = new java.util.HashSet<>();
        for (String k : seen) districts.add(k.substring(k.indexOf('|') + 1));
        distValue.setText(districts.size() + " / 18");
        String last = Explore.lastNew(this);
        lastValue.setText(last.isEmpty() ? "—" : last);
        map.setAccent(accent);
    }

    /** Lista dos concelhos visitados, por distrito. */
    private void showList() {
        Set<String> seen = Explore.visited(this);
        Map<String, List<String>> byDistrict = new TreeMap<>();
        for (String k : seen) {
            int bar = k.indexOf('|');
            byDistrict.computeIfAbsent(k.substring(bar + 1), d -> new ArrayList<>()).add(k.substring(0, bar));
        }
        Map<String, Integer> totals = new TreeMap<>();
        for (Concelhos.Concelho k : Concelhos.all(this)) totals.merge(k.district, 1, Integer::sum);
        StringBuilder text = new StringBuilder();
        if (seen.isEmpty()) text.append("Ainda nenhum. Cada concelho onde o Golf passar fica aqui.");
        for (Map.Entry<String, List<String>> e : byDistrict.entrySet()) {
            java.util.Collections.sort(e.getValue());
            if (text.length() > 0) text.append("\n\n");
            text.append(e.getKey().toUpperCase(PT)).append("  ·  ").append(e.getValue().size()).append(" de ")
                    .append(totals.getOrDefault(e.getKey(), 0)).append('\n')
                    .append(android.text.TextUtils.join(", ", e.getValue()));
        }
        Sheet.create(this, "Concelhos visitados (" + seen.size() + ")").width(860)
                .message(text.toString()).button("OK", true, null).show();
    }

    private LinearLayout card(LinearLayout parent, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(24, 0, 20, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(22f);
        bg.setColor(0xFF13161B);
        bg.setStroke(1, 0xFF22262D);
        box.setBackground(bg);
        TextView l = text(label, "700", 13, accent);
        l.setLetterSpacing(0.14f);
        box.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        if (parent.getChildCount() > 0) lp.topMargin = 12;
        parent.addView(box, lp);
        return box;
    }

    private TextView chip(String label, View.OnClickListener l) {
        TextView t = text(label, "600", 16, 0xFFC9CDD3);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setPadding(18, 0, 18, 0);
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout.LayoutParams chipLp(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48);
        if (gap) lp.leftMargin = 12;
        return lp;
    }

    private TextView text(String s, String weight, int sizePx, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Ui.font(this, weight));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        t.setMaxLines(1);
        return t;
    }
}
