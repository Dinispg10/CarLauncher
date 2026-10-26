package pt.meucarro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Estado do adaptador OBD2 (motor) partilhado entre o serviço que fala com o adaptador e os ecrãs.
 * Os valores são NaN quando o carro não os envia (ou ainda não foram lidos).
 */
public final class Obd {

    public enum State { NONE, CONNECTING, INIT, CONNECTED, NO_CAR, OFF }

    public interface Listener {
        void onObdChanged();
    }

    private Obd() {
    }

    // ---------- Estado ----------
    public static volatile State state = State.NONE;
    public static volatile String stateDetail = "";
    public static volatile String adapterName = "";

    public static volatile double rpm = Double.NaN;
    public static volatile double speed = Double.NaN;        // km/h
    public static volatile double coolant = Double.NaN;      // °C
    public static volatile double intakeTemp = Double.NaN;   // °C
    public static volatile double load = Double.NaN;         // %
    public static volatile double boost = Double.NaN;        // bar (acima da pressão atmosférica)
    public static volatile double maf = Double.NaN;          // g/s
    public static volatile double voltage = Double.NaN;      // V
    public static volatile double fuelLevel = Double.NaN;    // %
    public static volatile double fuelRateLh = Double.NaN;   // L/h (calculado)
    public static volatile double kmWithMil = Double.NaN;    // km com a luz acesa
    public static volatile boolean mil;
    public static volatile int dtcCount;
    public static volatile List<String> codes = new ArrayList<>();
    public static volatile List<String> pendingCodes = new ArrayList<>();
    public static volatile long codesReadAt;
    /** Há erros novos que o utilizador ainda não viu. */
    public static volatile boolean alertPending;

    // Informação (para o "ler tudo o que o carro suporta")
    public static volatile String elmVersion = "";
    public static volatile String protocol = "";
    public static volatile String vin = "";
    public static volatile String supported = "";

    /** Diesel: o computador de bordo do Golf 6 TDI tem depósito de 55 L. */
    public static final double TANK_L = 55;

    public static boolean engineOn() {
        return state == State.CONNECTED && !Double.isNaN(rpm) && rpm > 300;
    }

    /** Consumo instantâneo em L/100 km (ou NaN se parado / sem dados). */
    public static double l100Now() {
        if (Double.isNaN(fuelRateLh) || Double.isNaN(speed) || speed < 5) return Double.NaN;
        return fuelRateLh / speed * 100.0;
    }

    // ---------- Ouvintes (sempre chamados no fio principal) ----------
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean posted;

    public static void addListener(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    static void changed() {
        synchronized (Obd.class) {
            if (posted) return;
            posted = true;
        }
        MAIN.post(() -> {
            synchronized (Obd.class) {
                posted = false;
            }
            for (Listener l : new ArrayList<>(LISTENERS)) l.onObdChanged();
        });
    }

    static void clearValues() {
        rpm = speed = coolant = intakeTemp = load = boost = maf = voltage = fuelLevel = fuelRateLh = kmWithMil
                = Double.NaN;
    }

    // ---------- Preferências ----------
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    public static String savedMac(Context c) {
        return prefs(c).getString("obd_mac", null);
    }

    public static String savedName(Context c) {
        return prefs(c).getString("obd_name", "Adaptador");
    }

    public static void save(Context c, String mac, String name) {
        prefs(c).edit().putString("obd_mac", mac).putString("obd_name", name == null ? "Adaptador" : name).apply();
    }

    public static void forget(Context c) {
        prefs(c).edit().remove("obd_mac").remove("obd_name").apply();
    }

    /** Fator de correção do consumo (acertado pelo utilizador com o computador de bordo). */
    public static double fuelFactor(Context c) {
        return prefs(c).getFloat("obd_fuel_factor", 1f);
    }

    public static void setFuelFactor(Context c, double f) {
        prefs(c).edit().putFloat("obd_fuel_factor", (float) Math.max(0.3, Math.min(3.0, f))).apply();
    }

    /** Contadores que só sobem enquanto o adaptador está ligado (para o consumo de cada viagem). */
    public static double totalFuelL(Context c) {
        return prefs(c).getFloat("obd_fuel_l", 0f);
    }

    public static double totalKm(Context c) {
        return prefs(c).getFloat("obd_km", 0f);
    }

    static void addTotals(Context c, double litres, double km) {
        SharedPreferences p = prefs(c);
        p.edit().putFloat("obd_fuel_l", (float) (p.getFloat("obd_fuel_l", 0f) + litres))
                .putFloat("obd_km", (float) (p.getFloat("obd_km", 0f) + km)).apply();
    }

    /** O utilizador viu os erros atuais: não volta a avisar por estes. */
    public static void acknowledge(Context c) {
        if (simulating()) {
            alertPending = false;
            changed();
            return;
        }
        StringBuilder sb = new StringBuilder(mil ? "MIL" : "");
        for (String code : codes) sb.append(',').append(code);
        prefs(c).edit().putString("obd_ack", sb.toString()).apply();
        alertPending = false;
        changed();
    }

    // ---------- Teste simulado (só no ecrã; não envia nada ao carro) ----------
    public static volatile long simulateUntil;

    public static boolean simulating() {
        return System.currentTimeMillis() < simulateUntil;
    }

    /** Finge que a luz do motor acendeu com dois erros, durante 2 minutos. */
    public static void startSimulation() {
        simulateUntil = System.currentTimeMillis() + 120000;
        mil = true;
        dtcCount = 2;
        java.util.List<String> fake = new java.util.ArrayList<>();
        fake.add("P0401");
        fake.add("P2463");
        codes = fake;
        pendingCodes = new java.util.ArrayList<>();
        kmWithMil = 23;
        codesReadAt = System.currentTimeMillis();
        alertPending = true;
        log("TESTE: luz do motor simulada (P0401, P2463) durante 2 min");
        changed();
    }

    public static void stopSimulation() {
        simulateUntil = 0;
        mil = false;
        dtcCount = 0;
        codes = new java.util.ArrayList<>();
        kmWithMil = Double.NaN;
        alertPending = false;
        log("TESTE: simulação terminada (erros \"apagados\")");
        changed();
    }

    // ---------- Registo (para perceber problemas) ----------
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    static void log(String line) {
        synchronized (LOG) {
            LOG.addFirst(TIME.format(new Date()) + "  " + line);
            while (LOG.size() > 300) LOG.removeLast();
        }
    }

    public static String logText() {
        synchronized (LOG) {
            StringBuilder sb = new StringBuilder();
            for (String l : LOG) sb.append(l).append('\n');
            return sb.length() == 0 ? "Ainda sem registo." : sb.toString();
        }
    }

    public static String infoText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Estado: ").append(state).append(stateDetail.isEmpty() ? "" : " (" + stateDetail + ")").append('\n');
        sb.append("Adaptador: ").append(adapterName).append(elmVersion.isEmpty() ? "" : " · " + elmVersion).append('\n');
        sb.append("Protocolo: ").append(protocol.isEmpty() ? "—" : protocol).append('\n');
        sb.append("VIN: ").append(vin.isEmpty() ? "—" : vin).append('\n');
        sb.append("Dados que o motor envia: ").append(supported.isEmpty() ? "—" : supported).append('\n');
        return sb.toString();
    }
}
