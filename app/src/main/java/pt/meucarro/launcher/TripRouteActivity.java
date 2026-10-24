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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Percurso de uma viagem desenhado como linha luminosa, com os números ao lado. */
public class TripRouteActivity extends Activity implements TripService.Listener {

    public static final String EXTRA_START = "start";
    private static final Locale PT = Locale.forLanguageTag("pt-PT");

    private long start;
    private RouteView route;
    private TextView empty;
    private LinearLayout stats;
    private int accent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accent = Ui.effectiveAccent(this, MediaHub.get(this));
        start = getIntent().getLongExtra(EXTRA_START, 0);

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
        TextView title = text(titleFor(start), "600", 30, 0xFFF2F3F5);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = 22;
        bar.addView(title, tlp);
        TextView again = text("Ver outra vez", "600", 16, 0xFFC9CDD3);
        again.setGravity(Gravity.CENTER);
        again.setBackgroundResource(R.drawable.chip_bg);
        again.setPadding(18, 0, 18, 0);
        again.setOnClickListener(v -> route.replay());
        bar.addView(again, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56));

        // Percurso + números
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rlp.topMargin = 16;
        root.addView(row, rlp);

        FrameLayout mapCard = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(28f);
        bg.setColor(0xFF101318);
        bg.setStroke(1, 0xFF22262D);
        mapCard.setBackground(bg);
        mapCard.setClipToOutline(true);
        route = new RouteView(this);
        mapCard.addView(route, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        empty = text("Esta viagem não tem percurso guardado.\nAs viagens novas passam a ter.", "500", 20, 0xFF9AA0A6);
        empty.setGravity(Gravity.CENTER);
        empty.setMaxLines(3);
        mapCard.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        // legenda
        LinearLayout legend = new LinearLayout(this);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setPadding(22, 0, 22, 16);
        legend.addView(legendItem(RouteView.SLOW, "até 50"));
        legend.addView(legendItem(RouteView.MID, "50–90"));
        legend.addView(legendItem(RouteView.FAST, "mais de 90 km/h"));
        mapCard.addView(legend, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
        row.addView(mapCard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(250, ViewGroup.LayoutParams.MATCH_PARENT);
        slp.leftMargin = 18;
        row.addView(stats, slp);

        setContentView(frame);
        Ui.addClock(this);
        load(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
        Night.apply(this, Night.isNight(this));
        TripService.addListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        TripService.removeListener(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.applySystemBars(this);
    }

    private long lastLive;

    @Override
    public void onTripChanged() {
        // Viagem a decorrer: atualiza o desenho de 10 em 10 segundos, sem animar
        TripService.Trip cur = TripService.current(this);
        long now = System.currentTimeMillis();
        if (cur != null && cur.startMs == start && now - lastLive > 10000) {
            lastLive = now;
            load(false);
        }
    }

    private void load(boolean animate) {
        List<int[]> pts = TripService.route(this, start);
        route.setRoute(pts, animate);
        empty.setVisibility(pts.size() < 2 ? View.VISIBLE : View.GONE);

        TripService.Trip trip = findTrip();
        int max = 0;
        for (int[] p : pts) max = Math.max(max, p[2]);
        stats.removeAllViews();
        if (trip != null) {
            stat("DISTÂNCIA", TripService.formatKm(trip.km));
            stat("TEMPO", TripService.formatDuration(trip.durationMs));
            stat("MÉDIA", trip.durationMs > 60000 ? Math.round(trip.avgKmh()) + " km/h" : "—");
        }
        stat("MÁXIMA", max > 0 ? max + " km/h" : "—");
        if (trip != null) stat("CONSUMO", Double.isNaN(trip.l100) ? "—" : trip.l100Text() + "/100");
    }

    private TripService.Trip findTrip() {
        TripService.Trip cur = TripService.current(this);
        if (cur != null && cur.startMs == start) return cur;
        for (TripService.Trip t : TripService.history(this)) if (t.startMs == start) return t;
        return null;
    }

    private void stat(String label, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(24, 0, 16, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(22f);
        bg.setColor(0xFF13161B);
        bg.setStroke(1, 0xFF22262D);
        box.setBackground(bg);
        TextView l = text(label, "700", 13, accent);
        l.setLetterSpacing(0.14f);
        box.addView(l);
        box.addView(text(value, "600", 30, 0xFFF2F3F5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        if (stats.getChildCount() > 0) lp.topMargin = 12;
        stats.addView(box, lp);
    }

    private View legendItem(int color, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        View sw = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(3f);
        g.setColor(color);
        sw.setBackground(g);
        item.addView(sw, new LinearLayout.LayoutParams(22, 6));
        TextView t = text(label, "600", 15, 0xFF9AA0A6);
        t.setPadding(8, 0, 20, 0);
        item.addView(t);
        return item;
    }

    private static String titleFor(long ms) {
        if (ms == 0) return "Viagem";
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(ms);
        java.util.Calendar today = java.util.Calendar.getInstance();
        String time = new SimpleDateFormat("HH:mm", PT).format(new Date(ms));
        if (then.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
                && then.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)) {
            return "Hoje, " + time;
        }
        String day = new SimpleDateFormat("EEEE, d MMM", PT).format(new Date(ms)).replace(".", "");
        return day.substring(0, 1).toUpperCase(PT) + day.substring(1) + " · " + time;
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
