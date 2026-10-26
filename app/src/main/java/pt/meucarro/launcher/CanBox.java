package pt.meucarro.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Escuta (só escuta) os dados que a caixa CAN do rádio envia para a app CanSetting.
 * A app CanBus da XYGala reenvia cada mensagem da caixa como "xy.auto.canbus.RX_DATA_CON".
 * Guardamos a última mensagem de cada tipo para descobrir onde vêm os litros do depósito.
 * Não envia nada para a caixa nem para o carro.
 */
public final class CanBox {

    private static final Map<String, String> LATEST = new LinkedHashMap<>();
    private static final Map<String, Integer> COUNT = new LinkedHashMap<>();
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private static boolean registered;
    private static long total;
    private static long lastAt;

    /** Dados do painel do Golf, lidos da caixa CAN (NaN se ainda não chegaram). */
    public static volatile double fuelL = Double.NaN;      // litros no depósito
    public static volatile double odometerKm = Double.NaN; // km totais do carro
    public static volatile double outsideC = Double.NaN;   // temperatura exterior
    public static volatile double batteryV = Double.NaN;   // tensão da bateria (mais exata que a do adaptador)
    public static volatile long carDataAt;
    public static volatile String fuelSource = "";

    /** Os dados da caixa são recentes (menos de 30 s)? */
    public static boolean fresh() {
        return System.currentTimeMillis() - carDataAt < 30000;
    }

    private CanBox() {
    }

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            byte[] data = null;
            try {
                Object o = intent.getExtras() == null ? null : intent.getExtras().get(
                        "xy.auto.canbus.RX_DATA_CON".equals(intent.getAction()) ? "KEY_RXDATA" : "canbus_data");
                if (o instanceof byte[]) data = (byte[]) o;
            } catch (Exception ignored) {
            }
            if (data == null || data.length == 0) return;
            onFrame(intent.getAction().startsWith("xy") ? "rx" : "msg", data);
        }
    };

    /** Começa a escutar (chamado quando a app arranca). */
    public static synchronized void start(Context context) {
        if (registered) return;
        IntentFilter f = new IntentFilter();
        f.addAction("xy.auto.canbus.RX_DATA_CON");
        f.addAction("canbus.intent.action.msg");
        try {
            context.getApplicationContext().registerReceiver(RECEIVER, f);
            registered = true;
        } catch (Exception ignored) {
        }
    }

    private static synchronized void onFrame(String src, byte[] b) {
        total++;
        lastAt = System.currentTimeMillis();
        String hex = hex(b);
        // "tipo" = primeiros 2 bytes (cabeçalho + comando), que é o que costuma identificar a mensagem
        String type = src + ":" + hex(b, Math.min(2, b.length));
        if (b.length > 3 && ((b[0] & 0xFF) == 0x5A && (b[1] & 0xFF) == 0xA5)) type = src + ":5AA5/" + hex(b, 4).substring(9);
        LATEST.put(type, hex);
        COUNT.put(type, COUNT.containsKey(type) ? COUNT.get(type) + 1 : 1);
        RECENT.addFirst(TIME.format(new Date()) + " " + src + " " + hex);
        while (RECENT.size() > 150) RECENT.removeLast();
        decode(b);
    }

    /**
     * Caixa CAN "Raise" para VW PQ (Golf 6), mensagem 2E 41 com subtipo 02:
     * 2E 41 0D 02 [rpm 2B] [vel ×100 2B] [bateria ×100 2B] [temp. exterior ×10 2B] [km totais 3B] [litros] [soma]
     * Confirmado no carro: 781 rpm · 13,95 V · 282 327 km · 8 L.
     */
    private static void decode(byte[] b) {
        if (b.length >= 16 && (b[0] & 0xFF) == 0x2E && (b[1] & 0xFF) == 0x41 && (b[3] & 0xFF) == 0x02) {
            batteryV = u16(b, 8) / 100.0;
            int t = u16(b, 10);
            outsideC = (t & 0x8000) != 0 ? -((65536 - t) / 10.0) : t / 10.0;
            odometerKm = ((b[12] & 0xFF) << 16) | ((b[13] & 0xFF) << 8) | (b[14] & 0xFF);
            fuelL = b[15] & 0xFF;
            fuelSource = "2E41/02";
            carDataAt = System.currentTimeMillis();
            Obd.changed(); // os ecrãs do motor atualizam
        }
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("Mensagens da caixa CAN recebidas: ").append(total);
        if (lastAt > 0) sb.append(" (última às ").append(TIME.format(new Date(lastAt))).append(")");
        sb.append('\n');
        if (!Double.isNaN(fuelL)) {
            sb.append("Depósito: ").append((int) fuelL).append(" L · km totais: ").append((long) odometerKm)
                    .append(" · exterior: ").append(outsideC).append(" °C · bateria: ").append(batteryV).append(" V\n");
        }
        sb.append("\n== Última mensagem de cada tipo ==\n");
        for (Map.Entry<String, String> e : LATEST.entrySet()) {
            sb.append(e.getKey()).append("  ×").append(COUNT.get(e.getKey())).append("   ").append(e.getValue()).append('\n');
        }
        sb.append("\n== Últimas mensagens ==\n");
        for (String s : RECENT) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized long total() {
        return total;
    }

    private static String hex(byte[] b) {
        return hex(b, b.length);
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
