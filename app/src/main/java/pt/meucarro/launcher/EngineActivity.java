package pt.meucarro.launcher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ecrã Motor: dados ao vivo do adaptador OBD2, luz do motor e códigos de erro explicados.
 * Se ainda não houver adaptador escolhido, mostra os passos para o emparelhar.
 */
public class EngineActivity extends Activity implements Obd.Listener {

    private static final Locale PT = Locale.forLanguageTag("pt-PT");
    private static final int TEXT = 0xFFF2F3F5;
    private static final int MUTED = 0xFF9AA0A6;
    private static final int SOFT = 0xFFC9CDD3;
    private static final int CARD = 0xFF13161B;
    private static final int STROKE = 0xFF22262D;
    private static final int RED = 0xFFF87171;
    private static final int BLUE = 0xFF60A5FA;
    private static final int YELLOW = 0xFFFBBF24;
    private static final int GREEN = 0xFF4ADE80;
    private static final int REQ_BT = 7;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int accent;
    private FrameLayout content;
    private TextView statusText;
    private View statusDot;

    // Painel
    private Tile rpm, speed, temp, boost, battery, nowL, tripL, fuel;
    private TextView milTitle, milBody;
    private LinearLayout codesBox;
    private TextView clearBtn;

    // Emparelhar
    private LinearLayout deviceList;
    private TextView setupStatus;
    private final Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private BluetoothDevice pending;
    private boolean receiverOn;

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

        root.addView(topBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56));
        content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = 16;
        root.addView(content, clp);
        setContentView(frame);
        Ui.addClock(this);
        showRightView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
        Obd.addListener(this);
        ObdService.start(this);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Obd.removeListener(this);
    }

    @Override
    protected void onDestroy() {
        stopReceiver();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.applySystemBars(this);
    }

    @Override
    public void onObdChanged() {
        render();
    }

    private void showRightView() {
        content.removeAllViews();
        if (Obd.savedMac(this) == null) {
            content.addView(setupView());
        } else {
            stopReceiver();
            content.addView(dashboard());
        }
        render();
    }

    // ---------- Barra de cima ----------

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout back = new LinearLayout(this);
        back.setOrientation(LinearLayout.HORIZONTAL);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setBackgroundResource(R.drawable.pill_bg);
        back.setPadding(16, 0, 22, 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_back);
        back.addView(icon, new LinearLayout.LayoutParams(22, 22));
        TextView backText = text("Carro", "600", 17, TEXT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = 10;
        back.addView(backText, blp);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 52));

        TextView title = text("Motor", "600", 30, TEXT);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = 22;
        bar.addView(title, tlp);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setBackgroundResource(R.drawable.chip_bg);
        status.setPadding(16, 0, 18, 0);
        statusDot = new View(this);
        status.addView(statusDot, new LinearLayout.LayoutParams(12, 12));
        statusText = text("", "600", 16, SOFT);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = 10;
        status.addView(statusText, slp);
        bar.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48));

        TextView more = text("Mais", "600", 16, SOFT);
        more.setGravity(Gravity.CENTER);
        more.setBackgroundResource(R.drawable.chip_bg);
        more.setPadding(22, 0, 22, 0);
        more.setOnClickListener(v -> showMenu());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48);
        mlp.leftMargin = 12;
        bar.addView(more, mlp);
        return bar;
    }

    private void showMenu() {
        Sheet.create(this, "Motor").width(760)
                .item("Informação do carro", () -> Sheet.create(this, "Informação do carro").width(860)
                        .message(Obd.infoText()).button("OK", true, null).show())
                .item("Registo da ligação", () -> {
                    String log = Obd.logText();
                    String[] lines = log.split("\n");
                    StringBuilder first = new StringBuilder();
                    for (int i = 0; i < Math.min(40, lines.length); i++) first.append(lines[i]).append('\n');
                    Sheet.create(this, "Registo da ligação (mais recente em cima)").width(940)
                            .message(first.toString().trim()).button("OK", true, null).show();
                })
                .item("Acertar o consumo", this::calibrate)
                .item("Ler os erros outra vez", ObdService::readCodes)
                .item("Testar o aviso da luz do motor (simulado)", () -> Sheet.confirm(this,
                        "Testar o aviso?",
                        "A app finge durante 2 minutos que a luz do motor acendeu (P0401 e P2463). "
                                + "É só no ecrã: não envia nada ao carro. Vai para o ecrã inicial e aparece o aviso; "
                                + "depois experimente \"Ver detalhes\" e \"Apagar erros\".", "Testar", () -> {
                            Obd.startSimulation();
                            startActivity(new android.content.Intent(this, MainActivity.class)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
                        }))
                .item("Trocar de adaptador", () -> Sheet.confirm(this, "Trocar de adaptador?",
                        "A app esquece este adaptador. Depois escolhe outro na lista.", "Trocar", () -> {
                            ObdService.stop(this);
                            Obd.forget(this);
                            showRightView();
                        }))
                .button("Fechar", true, null)
                .show();
    }

    private void calibrate() {
        Sheet.create(this, "Acertar o consumo").width(820)
                .message("O consumo é uma estimativa. Faça uma viagem, compare a média desta app com a do "
                        + "computador de bordo do Golf e ajuste: se a app mostrar MAIS do que o carro, baixe.")
                .item("Correção atual", () -> String.format(PT, "%+.0f %%", (Obd.fuelFactor(this) - 1) * 100),
                        false, () -> { })
                .item("Baixar 10 %", null, false, () -> Obd.setFuelFactor(this, Obd.fuelFactor(this) * 0.90))
                .item("Baixar 3 %", null, false, () -> Obd.setFuelFactor(this, Obd.fuelFactor(this) * 0.97))
                .item("Subir 3 %", null, false, () -> Obd.setFuelFactor(this, Obd.fuelFactor(this) * 1.03))
                .item("Subir 10 %", null, false, () -> Obd.setFuelFactor(this, Obd.fuelFactor(this) * 1.10))
                .item("Repor", null, false, () -> Obd.setFuelFactor(this, 1.0))
                .button("OK", true, null)
                .show();
    }

    // ---------- Painel ----------

    private View dashboard() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = row();
        rpm = tile(row1, "ROTAÇÕES", "rpm", true);
        speed = tile(row1, "VELOCIDADE", "km/h", true);
        temp = tile(row1, "TEMPERATURA", "°C", true);
        boost = tile(row1, "TURBO", "bar", true);
        col.addView(row1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160));

        LinearLayout row2 = row();
        battery = tile(row2, "BATERIA", "V", false);
        nowL = tile(row2, "CONSUMO AGORA", "L/100", false);
        tripL = tile(row2, "CONSUMO DA VIAGEM", "L/100", false);
        fuel = tile(row2, "CARGA DO MOTOR", "%", false);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 128);
        r2.topMargin = 14;
        col.addView(row2, r2);

        // Luz do motor
        LinearLayout milCard = card();
        milCard.setOrientation(LinearLayout.VERTICAL);
        milCard.setPadding(28, 18, 24, 18);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        milTitle = text("LUZ DO MOTOR", "700", 15, accent);
        milTitle.setLetterSpacing(0.12f);
        head.addView(milTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clearBtn = text("Apagar erros", "600", 16, SOFT);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setBackgroundResource(R.drawable.chip_bg);
        clearBtn.setPadding(18, 0, 18, 0);
        clearBtn.setOnClickListener(v -> confirmClear());
        head.addView(clearBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 42));
        milCard.addView(head);
        milBody = text("", "500", 18, SOFT);
        milBody.setMaxLines(3);
        milBody.setSingleLine(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = 6;
        milCard.addView(milBody, blp);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        codesBox = new LinearLayout(this);
        codesBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(codesBox);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = 6;
        milCard.addView(scroll, slp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mlp.topMargin = 14;
        col.addView(milCard, mlp);
        return col;
    }

    private void confirmClear() {
        Sheet.confirm(this, "Apagar os erros do motor?",
                "Faça isto só depois de o problema estar resolvido: se a avaria continuar, a luz volta a acender. "
                        + "Melhor com o carro parado.", "Apagar", () -> {
                    if (Obd.simulating()) Obd.stopSimulation(); // teste: não envia nada ao carro
                    else ObdService.clearCodes();
                    Obd.log("Pedido: apagar erros");
                });
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(28f);
        bg.setColor(CARD);
        bg.setStroke(1, STROKE);
        c.setBackground(bg);
        return c;
    }

    private final class Tile {
        TextView label, value, unit, hint;
        View bar, barFill;
        LinearLayout box;
    }

    private Tile tile(LinearLayout parent, String label, String unit, boolean big) {
        Tile t = new Tile();
        LinearLayout c = card();
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(24, 16, 20, 14);
        t.box = c;
        t.label = text(label, "700", 13, MUTED);
        t.label.setLetterSpacing(0.12f);
        c.addView(t.label);
        View spacer = new View(this);
        c.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        LinearLayout vr = new LinearLayout(this);
        vr.setOrientation(LinearLayout.HORIZONTAL);
        vr.setGravity(Gravity.BOTTOM);
        t.value = text("—", big ? "300" : "500", big ? 62 : 44, TEXT);
        t.value.setIncludeFontPadding(false);
        vr.addView(t.value);
        t.unit = text(unit, "500", big ? 20 : 17, MUTED);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.leftMargin = 8;
        ulp.bottomMargin = big ? 8 : 5;
        vr.addView(t.unit, ulp);
        c.addView(vr);
        t.hint = text("", "500", 15, MUTED);
        c.addView(t.hint);
        if (big) {
            FrameLayout track = new FrameLayout(this);
            GradientDrawable tb = new GradientDrawable();
            tb.setCornerRadius(3f);
            tb.setColor(0xFF22262D);
            track.setBackground(tb);
            t.barFill = new View(this);
            track.addView(t.barFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
            t.bar = track;
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6);
            tlp.topMargin = 8;
            c.addView(track, tlp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (parent.getChildCount() > 0) lp.leftMargin = 14;
        parent.addView(c, lp);
        return t;
    }

    private void setBar(Tile t, double fraction, int color) {
        if (t.bar == null) return;
        int w = t.bar.getWidth();
        if (w == 0) {
            t.bar.post(() -> setBar(t, fraction, color));
            return;
        }
        double f = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(3f);
        g.setColor(color);
        t.barFill.setBackground(g);
        ViewGroup.LayoutParams lp = t.barFill.getLayoutParams();
        lp.width = (int) (w * f);
        t.barFill.setLayoutParams(lp);
    }

    // ---------- Mostrar dados ----------

    private void render() {
        renderStatus();
        if (rpm == null || Obd.savedMac(this) == null) return;
        boolean live = Obd.state == Obd.State.CONNECTED;

        rpm.value.setText(live ? fmt0(Obd.rpm) : "—");
        setBar(rpm, Obd.rpm / 5000.0, Obd.rpm > 4000 ? YELLOW : accent);
        speed.value.setText(live ? fmt0(Obd.speed) : "—");
        setBar(speed, Obd.speed / 200.0, accent);

        temp.value.setText(live ? fmt0(Obd.coolant) : "—");
        int tc = Double.isNaN(Obd.coolant) ? accent : Obd.coolant < 70 ? BLUE : Obd.coolant > 105 ? RED : GREEN;
        setBar(temp, (Obd.coolant - 40) / 80.0, tc);
        temp.hint.setText(!live || Double.isNaN(Obd.coolant) ? "" : Obd.coolant < 70 ? "Frio: evite puxar"
                : Obd.coolant > 105 ? "Quente demais!" : "Normal");
        temp.hint.setTextColor(tc);

        boost.value.setText(live ? fmt1(Obd.boost) : "—");
        setBar(boost, Obd.boost / 1.6, accent);
        boost.hint.setText(Double.isNaN(Obd.boost) && live ? "Não enviado pelo carro" : "");

        // Bateria: com o motor a trabalhar deve estar entre 12,3 e 15 V (carga inteligente da VW)
        battery.value.setText(fmt1(Obd.voltage));
        String bh = "";
        int bc = MUTED;
        if (!Double.isNaN(Obd.voltage)) {
            if (Obd.engineOn()) {
                // O Golf 6 tem alternador "inteligente": com a bateria cheia pode ficar nos 12,5–12,9 V
                if (Obd.voltage < 12.2) {
                    bh = "Não está a carregar";
                    bc = RED;
                } else if (Obd.voltage > 15.0) {
                    bh = "Tensão alta";
                    bc = YELLOW;
                } else {
                    bh = "A carregar";
                    bc = GREEN;
                }
            } else if (Obd.voltage < 12.0) {
                bh = "Bateria fraca";
                bc = RED;
            } else {
                bh = "Motor desligado";
            }
        }
        battery.hint.setText(bh);
        battery.hint.setTextColor(bc);

        double now = Obd.l100Now();
        if (live && Double.isNaN(now) && !Double.isNaN(Obd.fuelRateLh)) {
            nowL.value.setText(fmt1(Obd.fuelRateLh));
            nowL.unit.setText("L/h");
            nowL.hint.setText("Parado");
        } else {
            nowL.value.setText(live ? fmt1(now) : "—");
            nowL.unit.setText("L/100");
            nowL.hint.setText(live ? "Estimativa" : "");
        }
        TripService.Trip trip = TripService.current(this);
        tripL.value.setText(trip == null ? "—" : fmt1(trip.l100));
        tripL.hint.setText(trip == null ? "" : TripService.formatKm(trip.km));

        if (!Double.isNaN(Obd.fuelLevel)) {
            fuel.label.setText("DEPÓSITO");
            fuel.value.setText(fmt0(Obd.fuelLevel * Obd.TANK_L / 100));
            fuel.unit.setText("L");
            fuel.hint.setText(fmt0(Obd.fuelLevel) + " %");
        } else {
            fuel.label.setText("CARGA DO MOTOR");
            fuel.value.setText(live ? fmt0(Obd.load) : "—");
            fuel.unit.setText("%");
            fuel.hint.setText("");
        }

        renderCodes(live);
    }

    private void renderCodes(boolean live) {
        codesBox.removeAllViews();
        List<String> codes = Obd.codes;
        List<String> pendingCodes = Obd.pendingCodes;
        boolean haveRead = Obd.codesReadAt > 0;
        clearBtn.setVisibility(haveRead && (Obd.mil || !codes.isEmpty() || !pendingCodes.isEmpty())
                ? View.VISIBLE : View.GONE);
        if (!haveRead) {
            milTitle.setText("LUZ DO MOTOR");
            milTitle.setTextColor(accent);
            milBody.setText(live ? "A ler os erros…" : stateHelp());
            return;
        }
        if (!Obd.mil && codes.isEmpty() && pendingCodes.isEmpty()) {
            milTitle.setText("LUZ DO MOTOR · APAGADA");
            milTitle.setTextColor(GREEN);
            milBody.setText("✓ Sem erros no motor.");
            Obd.alertPending = false;
            return;
        }
        milTitle.setText(Obd.mil ? "LUZ DO MOTOR · ACESA" : "AVISOS DO MOTOR");
        milTitle.setTextColor(Obd.mil ? RED : YELLOW);
        String km = Double.isNaN(Obd.kmWithMil) || !Obd.mil ? "" : " · " + fmt0(Obd.kmWithMil) + " km com a luz acesa";
        milBody.setText(codes.size() + (codes.size() == 1 ? " erro" : " erros")
                + (pendingCodes.isEmpty() ? "" : " · " + pendingCodes.size() + " a confirmar") + km);
        for (String c : codes) codesBox.addView(codeRow(c, false));
        for (String c : pendingCodes) if (!codes.contains(c)) codesBox.addView(codeRow(c, true));
        if (Obd.alertPending) Obd.acknowledge(this); // já está a vê-los
    }

    private View codeRow(String code, boolean pendingCode) {
        Dtc.Info info = Dtc.info(code);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, 8, 0, 8);
        int color = pendingCode ? YELLOW : info.level >= 3 ? RED : info.level == 2 ? 0xFFFB923C : YELLOW;
        TextView c = text(code, "700", 22, color);
        r.addView(c, new LinearLayout.LayoutParams(110, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView d = text(info.text + (pendingCode ? " (a confirmar)" : ""), "600", 18, TEXT);
        d.setSingleLine(false);
        d.setMaxLines(2);
        txt.addView(d);
        TextView a = text(info.advice(), "500", 16, MUTED);
        a.setSingleLine(false);
        a.setMaxLines(2);
        txt.addView(a);
        r.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private String stateHelp() {
        switch (Obd.state) {
            case OFF:
                return "O Bluetooth do rádio está desligado.";
            case NO_CAR:
                return "O adaptador está ligado, mas o motor não responde. Ligue a ignição ou o motor.";
            case INIT:
                return "A falar com o carro…";
            case CONNECTING:
                return "À procura do adaptador. Confirme que está na ficha OBD e que a ignição está ligada.";
            default:
                return "";
        }
    }

    private void renderStatus() {
        String label;
        int color;
        if (Obd.savedMac(this) == null) {
            label = "Sem adaptador";
            color = MUTED;
        } else {
            switch (Obd.state) {
                case CONNECTED:
                    label = "Ligado";
                    color = GREEN;
                    break;
                case NO_CAR:
                    label = "À espera do motor";
                    color = YELLOW;
                    break;
                case INIT:
                    label = "A ligar ao carro…";
                    color = YELLOW;
                    break;
                case OFF:
                    label = "Bluetooth desligado";
                    color = RED;
                    break;
                default:
                    label = "À procura do adaptador";
                    color = MUTED;
            }
        }
        statusText.setText(label);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        statusDot.setBackground(dot);
    }

    // ---------- Emparelhar o adaptador ----------

    private View setupView() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = card();
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(30, 24, 28, 24);
        TextView h = text("LIGAR O ADAPTADOR OBD2", "700", 15, accent);
        h.setLetterSpacing(0.12f);
        left.addView(h);
        TextView steps = text("1.  Ligue o adaptador na ficha OBD, por baixo do volante (lado esquerdo).\n\n"
                + "2.  Ligue a ignição ou o motor. As luzes do adaptador acendem.\n\n"
                + "3.  Carregue em \"Procurar adaptador\" e toque nele na lista "
                + "(costuma chamar-se OBDII, V-LINK ou iCar).\n\n"
                + "4.  Se o rádio pedir um código, é 1234 (ou 0000).\n\n"
                + "Só é preciso fazer isto uma vez: depois a ligação é automática.", "500", 19, SOFT);
        steps.setSingleLine(false);
        steps.setMaxLines(20);
        steps.setLineSpacing(2f, 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = 14;
        left.addView(steps, slp);
        TextView search = text("Procurar adaptador", "700", 19, 0xFF0B0D10);
        search.setGravity(Gravity.CENTER);
        GradientDrawable sb = new GradientDrawable();
        sb.setCornerRadius(26f);
        sb.setColor(accent);
        search.setBackground(sb);
        search.setOnClickListener(v -> discover());
        left.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 58));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout right = card();
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(26, 22, 26, 22);
        TextView rh = text("APARELHOS", "700", 15, accent);
        rh.setLetterSpacing(0.12f);
        right.addView(rh);
        setupStatus = text("Carregue em \"Procurar adaptador\".", "500", 17, MUTED);
        setupStatus.setSingleLine(false);
        setupStatus.setMaxLines(3);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = 8;
        right.addView(setupStatus, stlp);
        ScrollView scroll = new ScrollView(this);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(deviceList);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sclp.topMargin = 10;
        right.addView(scroll, sclp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rlp.leftMargin = 18;
        row.addView(right, rlp);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            try {
                for (BluetoothDevice d : adapter.getBondedDevices()) devices.put(d.getAddress(), d);
            } catch (SecurityException ignored) {
            }
        }
        main.post(this::renderDevices);
        return row;
    }

    private static boolean looksLikeObd(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("obd") || n.contains("vlink") || n.contains("v-link") || n.contains("icar")
                || n.contains("vgate") || n.contains("elm") || n.contains("android-vlink");
    }

    private void renderDevices() {
        if (deviceList == null) return;
        deviceList.removeAllViews();
        // Os que parecem adaptadores OBD primeiro
        for (int pass = 0; pass < 2; pass++) {
            for (BluetoothDevice d : devices.values()) {
                String name = name(d);
                if ((pass == 0) != looksLikeObd(name)) continue;
                deviceList.addView(deviceRow(d, name, pass == 0));
            }
        }
    }

    private View deviceRow(BluetoothDevice d, String name, boolean obd) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(20, 10, 20, 10);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20f);
        bg.setColor(obd ? 0xFF1B2A2A : 0xFF181B21);
        bg.setStroke(obd ? 2 : 1, obd ? accent : STROKE);
        r.setBackground(bg);
        r.addView(text(name, "600", 20, TEXT));
        boolean bonded = false;
        try {
            bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException ignored) {
        }
        r.addView(text((obd ? "Parece o adaptador · " : "") + (bonded ? "emparelhado" : "novo"), "500", 15,
                obd ? accent : MUTED));
        r.setOnClickListener(v -> choose(d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10;
        r.setLayoutParams(lp);
        return r;
    }

    private String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    private boolean hasPermissions() {
        String[] needed = Build.VERSION.SDK_INT >= 31
                ? new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(needed, REQ_BT);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) discover();
    }

    private void discover() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            setupStatus.setText("O Bluetooth do rádio está desligado.");
            return;
        }
        if (!hasPermissions()) return;
        startReceiver();
        try {
            adapter.cancelDiscovery();
            adapter.startDiscovery();
            setupStatus.setText("A procurar (12 s)… O adaptador tem de estar na ficha com a ignição ligada.");
        } catch (SecurityException e) {
            setupStatus.setText("Sem autorização para procurar aparelhos.");
        }
    }

    private void choose(BluetoothDevice d) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        boolean wasDiscovering = false;
        try {
            wasDiscovering = adapter.isDiscovering();
            adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }
        int bond = BluetoothDevice.BOND_NONE;
        try {
            bond = d.getBondState();
        } catch (SecurityException ignored) {
        }
        if (bond == BluetoothDevice.BOND_BONDED) {
            use(d);
            return;
        }
        if (bond == BluetoothDevice.BOND_BONDING) {
            setupStatus.setText("Já está a emparelhar com " + name(d) + "… aguarde.");
            return;
        }
        startReceiver();
        pending = d;
        setupStatus.setText("A emparelhar com " + name(d) + "…");
        // Alguns rádios recusam emparelhar enquanto ainda estão a procurar: espera um pouco
        main.postDelayed(() -> {
            boolean ok = false;
            try {
                ok = d.createBond();
            } catch (Exception ignored) {
            }
            if (!ok) {
                // O rádio recusou o pedido: guarda na mesma; a ligação emparelha sozinha
                Obd.log("createBond recusado; a tentar ligar diretamente");
                use(d);
            }
        }, wasDiscovering ? 1800 : 300);
    }

    private void use(BluetoothDevice d) {
        pending = null;
        Obd.save(this, d.getAddress(), name(d));
        Obd.log("Adaptador escolhido: " + name(d) + " (" + d.getAddress() + ")");
        ObdService.restart(this);
        showRightView();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (BluetoothDevice.ACTION_FOUND.equals(action) && d != null) {
                devices.put(d.getAddress(), d);
                renderDevices();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (setupStatus != null && pending == null) {
                    setupStatus.setText(devices.isEmpty() ? "Nada encontrado. Confirme a ignição e procure outra vez."
                            : "Toque no adaptador. Se não aparecer, procure outra vez.");
                }
            } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action) && d != null) {
                // O adaptador pede código: responde 1234 sozinho (o habitual nos Vgate)
                int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                    try {
                        if (d.setPin("1234".getBytes("US-ASCII"))) {
                            Obd.log("Código 1234 enviado ao adaptador");
                            abortBroadcast();
                        }
                    } catch (Exception ignored) {
                    }
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action) && d != null && pending != null
                    && pending.getAddress().equals(d.getAddress())) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (state == BluetoothDevice.BOND_BONDED) {
                    use(d);
                } else if (state == BluetoothDevice.BOND_NONE) {
                    pending = null;
                    setupStatus.setText("Não emparelhou. Toque outra vez no adaptador; se pedir código, use 1234.");
                }
            }
        }
    };

    private void startReceiver() {
        if (receiverOn) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        registerReceiver(receiver, f);
        receiverOn = true;
    }

    private void stopReceiver() {
        if (!receiverOn) return;
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        receiverOn = false;
        try {
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
        } catch (Exception ignored) {
        }
    }

    // ---------- Ajudas ----------

    private static String fmt0(double v) {
        return Double.isNaN(v) ? "—" : String.valueOf(Math.round(v));
    }

    private static String fmt1(double v) {
        return Double.isNaN(v) ? "—" : String.format(PT, "%.1f", v);
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
