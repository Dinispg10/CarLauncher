package pt.meucarro.launcher;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Ecrã Carro: viagem atual em destaque, motor (OBD2, em breve) e as últimas 10 viagens. */
public class TripsActivity extends Activity implements TripService.Listener, Obd.Listener {

    private static final Locale PT = Locale.forLanguageTag("pt-PT");

    private int accent;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accent = Ui.effectiveAccent(this, MediaHub.get(this));

        ScaleFrame frame = new ScaleFrame(this);
        getLayoutInflater().inflate(R.layout.layout_trips, frame, true);
        setContentView(frame);
        Ui.addClock(this);
        Ui.style(frame, accent);

        list = findViewById(R.id.trips_list);

        // Dados do carro: abre a app CAN do rádio (a que mostra combustível, temperatura, etc.)
        android.view.View carCard = findViewById(R.id.car_data_card);
        carCard.setOnClickListener(v -> openCarApp());
        carCard.setOnLongClickListener(v -> {
            chooseCarApp();
            return true;
        });
        findViewById(R.id.cur_card).setOnClickListener(v -> {
            TripService.Trip cur = TripService.current(this);
            if (cur != null) openRoute(cur.startMs);
        });
        findViewById(R.id.btn_explore).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, ExploreActivity.class)));
        findViewById(R.id.engine_card).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, EngineActivity.class)));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_new_trip).setOnClickListener(v ->
                Sheet.confirm(this, "Começar nova viagem?",
                        "A viagem atual fica guardada na lista das últimas viagens.",
                        "Começar", () -> {
                            TripService.reset(this);
                            render();
                        }));
    }

    private static final String[] CAR_APP_KEYWORDS = {
            "cansetting", "can setting", "canbus", "canbox", "original car", "car setting", "carsetting",
            "car info", "carinfo", "vehicle",
    };

    private void openCarApp() {
        android.content.SharedPreferences prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        String pkg = prefs.getString("slot_car_pkg", null);
        String cls = prefs.getString("slot_car_cls", null);
        if (pkg == null) {
            AppUtils.AppEntry found = AppUtils.detect(AppUtils.listApps(this, false), CAR_APP_KEYWORDS);
            if (found != null) {
                pkg = found.pkg;
                cls = found.cls;
                prefs.edit().putString("slot_car_pkg", pkg).putString("slot_car_cls", cls).apply();
            }
        }
        if (!AppUtils.launch(this, pkg, cls)) chooseCarApp();
    }

    private void chooseCarApp() {
        Sheet sheet = Sheet.create(this, "Que app mostra os dados do carro?")
                .message("Normalmente chama-se CanSetting, Car Setting ou parecido.");
        for (AppUtils.AppEntry app : AppUtils.listApps(this, true)) {
            sheet.item(app.icon, app.label, () -> {
                getSharedPreferences("launcher", MODE_PRIVATE).edit()
                        .putString("slot_car_pkg", app.pkg).putString("slot_car_cls", app.cls).apply();
                AppUtils.launch(this, app.pkg, app.cls);
            });
        }
        sheet.button("Cancelar", false, null).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
        Night.apply(this, Night.isNight(this));
        TripService.addListener(this);
        Obd.addListener(this);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TripService.removeListener(this);
        Obd.removeListener(this);
    }

    @Override
    public void onObdChanged() {
        renderEngine();
    }

    /** Resumo do motor no cartão "MOTOR". */
    private void renderEngine() {
        TextView line = findViewById(R.id.engine_line);
        if (Obd.savedMac(this) == null) {
            line.setText("Ligar o adaptador");
            line.setTextColor(0xFF9AA0A6);
            return;
        }
        if (Obd.state == Obd.State.CONNECTED) {
            if (Obd.mil || !Obd.codes.isEmpty()) {
                line.setText("⚠ " + (Obd.codes.isEmpty() ? "Luz do motor" : Obd.codes.get(0)));
                line.setTextColor(0xFFF87171);
                return;
            }
            StringBuilder sb = new StringBuilder();
            if (!Double.isNaN(CanBox.fuelL)) sb.append(Math.round(CanBox.fuelL)).append(" L");
            if (!Double.isNaN(Obd.coolant)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(Math.round(Obd.coolant)).append(" °C");
            }
            if (sb.length() < 12 && !Double.isNaN(Obd.voltage)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(String.format(PT, "%.1f V", CanBox.fresh() ? CanBox.batteryV : Obd.voltage));
            }
            line.setText(sb.length() == 0 ? "Ligado" : sb.toString());
            line.setTextColor(0xFFF2F3F5);
        } else {
            line.setText(Obd.state == Obd.State.NO_CAR ? "À espera do motor" : "A ligar…");
            line.setTextColor(0xFF9AA0A6);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.applySystemBars(this);
    }

    @Override
    public void onTripChanged() {
        render();
    }

    private void render() {
        renderEngine();
        // ---- Viagem atual ----
        TripService.Trip current = TripService.current(this);
        TextView state = findViewById(R.id.cur_state);
        TextView km = findViewById(R.id.cur_km);
        TextView time = findViewById(R.id.cur_time);
        TextView avg = findViewById(R.id.cur_avg);
        TextView since = findViewById(R.id.cur_since);
        if (current != null) {
            state.setText("A DECORRER");
            state.setTextColor(accent);
            km.setText(current.km < 10
                    ? String.format(PT, "%.1f", current.km)
                    : String.valueOf(Math.round(current.km)));
            time.setText(TripService.formatDuration(current.durationMs));
            avg.setText(current.durationMs > 60000 ? Math.round(current.avgKmh()) + " km/h" : "—");
            ((TextView) findViewById(R.id.cur_l100)).setText(current.l100Text());
            since.setText("Desde as " + new SimpleDateFormat("HH:mm", PT).format(new Date(current.startMs)));
        } else {
            state.setText("PARADO");
            state.setTextColor(0xFF9AA0A6);
            km.setText("0");
            time.setText("—");
            avg.setText("—");
            ((TextView) findViewById(R.id.cur_l100)).setText("—");
            since.setText("Começa sozinha quando o carro andar");
        }

        // ---- Últimas viagens ----
        List<TripService.Trip> past = TripService.history(this);
        double totalKm = 0;
        long totalMs = 0;
        for (TripService.Trip t : past) {
            totalKm += t.km;
            totalMs += t.durationMs;
        }
        ((TextView) findViewById(R.id.totals)).setText(past.isEmpty() ? ""
                : past.size() + (past.size() == 1 ? " viagem · " : " viagens · ")
                + TripService.formatKm(totalKm) + " · " + TripService.formatDuration(totalMs));

        list.removeAllViews();
        if (past.isEmpty()) {
            float min = TripService.minKm(this);
            TextView empty = text("Ainda não há viagens guardadas. Quando o carro fica desligado mais de 10 minutos, "
                    + "a viagem aparece aqui" + (min > 0.2f ? " (se tiver pelo menos " + minLabel(min) + ")" : "")
                    + ".", "500", 18, 0xFF9AA0A6);
            empty.setMaxLines(4);
            empty.setPadding(6, 8, 6, 0);
            list.addView(empty);
            return;
        }
        for (TripService.Trip t : past) list.addView(tripRow(t));
    }

    private LinearLayout tripRow(TripService.Trip trip) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(20, 0, 20, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20f);
        bg.setColor(0xFF13161B);
        bg.setStroke(1, 0xFF22262D);
        row.setBackground(bg);
        row.setOnClickListener(v -> openRoute(trip.startMs));
        row.setOnLongClickListener(v -> {
            tripMenu(trip);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64);
        lp.bottomMargin = 10;
        row.setLayoutParams(lp);

        row.addView(text(whenLabel(trip.startMs), "600", 19, 0xFFF2F3F5),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        String[] values = {
                TripService.formatKm(trip.km),
                TripService.formatDuration(trip.durationMs),
                trip.durationMs > 60000 ? Math.round(trip.avgKmh()) + " km/h" : "—",
                trip.l100Text(),
        };
        int[] colors = {0xFFF2F3F5, 0xFFC9CDD3, 0xFFC9CDD3, 0xFFC9CDD3};
        for (int i = 0; i < values.length; i++) {
            TextView t = text(values[i], i == 0 ? "600" : "500", 19, colors[i]);
            t.setGravity(Gravity.END);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    /** Toque longo numa viagem: apagar esta, apagar todas ou escolher o mínimo de km. */
    private void tripMenu(TripService.Trip trip) {
        Sheet.create(this, whenLabel(trip.startMs) + " · " + TripService.formatKm(trip.km))
                .item("Ver o percurso", () -> openRoute(trip.startMs))
                .item("Apagar esta viagem", () -> {
                    TripService.deleteTrip(this, trip.startMs);
                    render();
                })
                .item("Guardar só viagens a partir de", () -> minLabel(TripService.minKm(this)), true, this::chooseMinKm)
                .item("Apagar todas as viagens", () -> Sheet.confirm(this, "Apagar todas as viagens?",
                        "A lista fica vazia. Os percursos guardados também são apagados.", "Apagar", () -> {
                            TripService.clearHistory(this);
                            render();
                        }))
                .button("Fechar", true, null)
                .show();
    }

    static String minLabel(float km) {
        return km <= 0.2f ? "Todas" : Math.round(km) + " km";
    }

    private void chooseMinKm() {
        chooseMinKm(this, this::render);
    }

    /** Escolher o mínimo de km para uma viagem ficar guardada (também usado nas definições). */
    static void chooseMinKm(Activity a, Runnable after) {
        float[] values = {0f, 5f, 10f, 15f};
        Sheet sheet = Sheet.create(a, "Guardar viagens a partir de")
                .message("As viagens mais curtas não ficam na lista. As que já lá estão e são mais curtas "
                        + "também são apagadas.");
        for (float v : values) {
            sheet.item(minLabel(v), () -> Math.abs(TripService.minKm(a) - v) < 0.01f ? "Em uso" : null, true, () -> {
                TripService.setMinKm(a, v);
                if (after != null) after.run();
            });
        }
        sheet.button("Cancelar", false, null).show();
    }

    private void openRoute(long tripStart) {
        startActivity(new android.content.Intent(this, TripRouteActivity.class)
                .putExtra(TripRouteActivity.EXTRA_START, tripStart));
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

    /** "Hoje, 08:15", "Ontem, 18:40" ou "seg, 20 out · 09:05". */
    private static String whenLabel(long ms) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ms);
        Calendar today = Calendar.getInstance();
        String time = new SimpleDateFormat("HH:mm", PT).format(new Date(ms));
        if (sameDay(then, today)) return "Hoje, " + time;
        today.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, today)) return "Ontem, " + time;
        String day = new SimpleDateFormat("EEE, d MMM", PT).format(new Date(ms)).replace(".", "");
        return day + " · " + time;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
