package pt.meucarro.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Fala com o adaptador OBD2 (ELM327, ex.: Vgate iCar Pro) por Bluetooth "porta série".
 * Liga-se sozinho quando o carro arranca e volta a tentar se a ligação cair.
 * Só LÊ dados do motor; a única coisa que escreve é "apagar erros", e só quando o utilizador pede.
 */
public class ObdService extends Service {

    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int NOTIFICATION_ID = 44;
    private static final int ALERT_ID = 45;

    private static volatile boolean clearRequested;
    private static volatile boolean readRequested;
    private static ObdService running;

    public static void start(Context context) {
        if (Obd.savedMac(context) == null) return;
        Intent intent = new Intent(context, ObdService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    /** Volta a ligar do zero (ex.: depois de escolher outro adaptador). */
    public static void restart(Context context) {
        context.stopService(new Intent(context, ObdService.class));
        start(context);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ObdService.class));
        Obd.state = Obd.State.NONE;
        Obd.clearValues();
        Obd.changed();
    }

    /** Pede para apagar os códigos de erro (e apagar a luz do motor). */
    public static void clearCodes() {
        clearRequested = true;
    }

    public static void readCodes() {
        readRequested = true;
    }

    // ---------- Serviço ----------

    private volatile boolean stopped;
    private Thread worker;
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;
    private final byte[] buf = new byte[512];
    private int timeouts;
    private boolean can;
    private final Set<String> supportedPids = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("obd", "Motor (OBD2)", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            NotificationChannel alert = new NotificationChannel("obd_alert", "Luz do motor",
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(alert);
        }
        // Se o adaptador pedir código ao emparelhar, responde sozinho (1234, o dos Vgate; depois 0000)
        android.content.IntentFilter f = new android.content.IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.setPriority(android.content.IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        registerReceiver(pairing, f);
    }

    private int pinTries;
    private final android.content.BroadcastReceiver pairing = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String mac = Obd.savedMac(context);
            if (d == null || mac == null || !mac.equals(d.getAddress())) return;
            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
            Obd.log("O adaptador pediu emparelhamento (tipo " + variant + ")");
            if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                String pin = pinTries++ % 2 == 0 ? "1234" : "0000";
                try {
                    if (d.setPin(pin.getBytes("US-ASCII"))) {
                        Obd.log("Código " + pin + " enviado");
                        abortBroadcast();
                    }
                } catch (Exception ignored) {
                }
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("A procurar o adaptador do motor"));
        if (Obd.savedMac(this) == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            stopped = false;
            worker = new Thread(this::loop, "OBD");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        try {
            unregisterReceiver(pairing);
        } catch (Exception ignored) {
        }
        closeSocket();
        if (worker != null) worker.interrupt();
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "obd") : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_car)
                .setContentTitle("Launcher do Carro")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(text));
        } catch (Exception ignored) {
        }
    }

    private void setState(Obd.State s, String detail) {
        if (Obd.state != s || !detail.equals(Obd.stateDetail)) {
            Obd.state = s;
            Obd.stateDetail = detail;
            Obd.log("Estado: " + s + (detail.isEmpty() ? "" : " · " + detail));
            Obd.changed();
            String text;
            switch (s) {
                case CONNECTED:
                    text = "Ligado ao motor";
                    break;
                case NO_CAR:
                    text = "Adaptador ligado, à espera do motor";
                    break;
                default:
                    text = "A procurar o adaptador do motor";
            }
            updateNotification(text);
        }
    }

    // ---------- Ciclo principal ----------

    private void loop() {
        int failures = 0;
        while (!stopped) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                setState(Obd.State.OFF, "Bluetooth do rádio desligado");
                sleep(10000);
                continue;
            }
            String mac = Obd.savedMac(this);
            if (mac == null) break;
            BluetoothDevice device;
            try {
                device = adapter.getRemoteDevice(mac);
            } catch (Exception e) {
                break;
            }
            Obd.adapterName = Obd.savedName(this);
            setState(Obd.State.CONNECTING, failures == 0 ? "" : "tentativa " + (failures + 1));
            try {
                adapter.cancelDiscovery();
            } catch (Exception ignored) {
            }
            ensureBonded(device);
            socket = open(device);
            if (socket == null) {
                failures++;
                // O adaptador acorda com a ignição: tenta depressa ao início, depois com mais calma
                long wait = failures < 6 ? 5000 : failures < 20 ? 20000 : 60000;
                setState(Obd.State.CONNECTING, "o adaptador não respondeu; nova tentativa em " + wait / 1000 + " s");
                sleep(wait);
                continue;
            }
            failures = 0;
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();
                session();
            } catch (Exception e) {
                Obd.log("Ligação terminou: " + e.getMessage());
            }
            closeSocket();
            Obd.clearValues();
            Obd.changed();
            sleep(3000);
        }
        setState(Obd.State.NONE, "");
        stopSelf();
    }

    /** Mostra no registo o estado do adaptador e, se não estiver emparelhado, tenta emparelhar. */
    private void ensureBonded(BluetoothDevice d) {
        int bond = BluetoothDevice.BOND_NONE;
        String name = "?";
        boolean spp = false;
        try {
            bond = d.getBondState();
            name = d.getName();
            android.os.ParcelUuid[] uuids = d.getUuids();
            if (uuids != null) for (android.os.ParcelUuid u : uuids) if (SPP.equals(u.getUuid())) spp = true;
        } catch (Exception ignored) {
        }
        Obd.log("Adaptador \"" + name + "\" " + d.getAddress() + " · "
                + (bond == BluetoothDevice.BOND_BONDED ? "emparelhado" : bond == BluetoothDevice.BOND_BONDING
                ? "a emparelhar" : "NÃO emparelhado") + " · porta série anunciada: " + (spp ? "sim" : "não/desconhecido"));
        if (bond != BluetoothDevice.BOND_NONE) return;
        try {
            Obd.log("A pedir emparelhamento…");
            d.createBond();
            for (int i = 0; i < 40 && !stopped; i++) { // até 20 s
                sleep(500);
                int b = d.getBondState();
                if (b == BluetoothDevice.BOND_BONDED) {
                    Obd.log("Emparelhado ✓");
                    return;
                }
                if (b == BluetoothDevice.BOND_NONE && i > 4) break;
            }
            Obd.log("Não emparelhou (o adaptador está aceso e com o motor ligado?)");
        } catch (Exception e) {
            Obd.log("Erro ao emparelhar: " + e.getMessage());
        }
    }

    private BluetoothSocket open(BluetoothDevice d) {
        String[] ways = {"segura", "sem segurança", "canal 1", "canal 1 sem segurança"};
        for (int i = 0; i < ways.length && !stopped; i++) {
            BluetoothSocket s = null;
            try {
                if (i == 0) s = d.createRfcommSocketToServiceRecord(SPP);
                else if (i == 1) s = d.createInsecureRfcommSocketToServiceRecord(SPP);
                else {
                    Method m = d.getClass().getMethod(i == 2 ? "createRfcommSocket" : "createInsecureRfcommSocket",
                            int.class);
                    s = (BluetoothSocket) m.invoke(d, 1);
                }
                s.connect();
                Obd.log("Ligado ao adaptador (" + ways[i] + ") ✓");
                return s;
            } catch (Exception e) {
                Obd.log("· forma " + ways[i] + " falhou: " + e.getMessage());
                try {
                    if (s != null) s.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    /** Uma sessão com o adaptador: preparar, encontrar o carro e ler dados até a ligação cair. */
    private void session() throws Exception {
        timeouts = 0;
        setState(Obd.State.INIT, "a preparar o adaptador");
        String id = cmd("ATZ", 5000);
        sleep(500);
        if (id != null && id.contains("ELM")) {
            Obd.elmVersion = id.substring(id.indexOf("ELM")).split("[\\r\\n]")[0].trim();
        }
        for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"}) cmd(c, 2000);

        // Encontrar o carro (com a ignição desligada não há resposta: fica a tentar)
        while (!stopped) {
            setState(Obd.State.INIT, "a procurar o protocolo do carro");
            String r = cmd("0100", 15000);
            int[] b = pid(r, "00");
            if (b != null && b.length >= 4) {
                addSupported(0x00, b);
                break;
            }
            readVoltage();
            setState(Obd.State.NO_CAR, "o motor não respondeu (ignição desligada?)");
            Obd.changed();
            sleep(8000);
        }
        if (stopped) return;

        String dpn = clean(cmd("ATDPN", 2000));
        String dp = cmd("ATDP", 2000);
        Obd.protocol = (dp == null ? "" : dp.trim()) + " (" + dpn + ")";
        String num = dpn.replace("A", "");
        can = num.equals("6") || num.equals("7") || num.equals("8") || num.equals("9");

        if (supportedPids.contains("20")) addSupported(0x20, pid(cmd("0120", 3000), "20"));
        if (supportedPids.contains("40")) addSupported(0x40, pid(cmd("0140", 3000), "40"));
        List<String> sorted = new ArrayList<>(supportedPids);
        java.util.Collections.sort(sorted);
        Obd.supported = android.text.TextUtils.join(" ", sorted);
        Obd.log("Dados suportados: " + Obd.supported);
        readVin();
        readMilAndCodes(true);

        setState(Obd.State.CONNECTED, "");
        long lastTick = System.currentTimeMillis();
        long lastFlush = lastTick;
        double litres = 0, km = 0;
        int cycle = 0, noRpm = 0;
        while (!stopped) {
            cycle++;
            // Rápidos
            Obd.rpm = value("0C", b -> (b[0] * 256 + b[1]) / 4.0);
            Obd.speed = value("0D", b -> b[0]);
            Obd.load = value("04", b -> b[0] * 100.0 / 255);
            if (supportedPids.contains("10")) Obd.maf = value("10", b -> (b[0] * 256 + b[1]) / 100.0);
            if (supportedPids.contains("0B")) {
                double map = value("0B", b -> b[0]);
                double baro = Double.isNaN(baroKpa) ? 101 : baroKpa;
                Obd.boost = Double.isNaN(map) ? Double.NaN : Math.max(0, (map - baro) / 100.0);
            }
            double rate5e = supportedPids.contains("5E") ? value("5E", b -> (b[0] * 256 + b[1]) / 20.0) : Double.NaN;

            // Lentos
            if (cycle % 4 == 1) {
                Obd.coolant = value("05", b -> b[0] - 40);
                if (supportedPids.contains("0F")) Obd.intakeTemp = value("0F", b -> b[0] - 40);
                if (supportedPids.contains("2F")) Obd.fuelLevel = value("2F", b -> b[0] * 100.0 / 255);
                if (supportedPids.contains("21")) Obd.kmWithMil = value("21", b -> b[0] * 256 + b[1]);
                readVoltage();
            }
            if (cycle % 60 == 2 && supportedPids.contains("33")) baroKpa = value("33", b -> b[0]);
            if (cycle % 25 == 0 || readRequested) {
                readRequested = false;
                readMilAndCodes(false);
            }
            if (clearRequested && Obd.simulating()) {
                clearRequested = false;
                Obd.stopSimulation(); // teste: não envia nada ao carro
            }
            if (clearRequested) {
                clearRequested = false;
                String r = cmd("04", 5000);
                boolean ok = r != null && clean(r).contains("44");
                Obd.log(ok ? "Erros apagados" : "O carro não apagou os erros: " + r);
                sleep(1500);
                readMilAndCodes(true);
                Obd.acknowledge(this);
            }

            // Consumo
            double fuel;
            double factor = Obd.fuelFactor(this);
            if (!Double.isNaN(rate5e)) {
                fuel = rate5e * factor;
            } else if (!Double.isNaN(Obd.rpm) && !Double.isNaN(Obd.load)) {
                // Estimativa para o 2.0 TDI: gasóleo injetado ≈ carga × 45 mg por injeção, 4 cilindros
                double gPerS = Obd.load / 100.0 * 0.045 * Obd.rpm / 30.0;
                fuel = gPerS * 3600 / 832.0 * factor;
            } else {
                fuel = Double.NaN;
            }
            Obd.fuelRateLh = Obd.rpm > 300 ? fuel : (Double.isNaN(Obd.rpm) ? Double.NaN : 0);

            long now = System.currentTimeMillis();
            double dtH = (now - lastTick) / 3600000.0;
            lastTick = now;
            if (dtH > 0 && dtH < 0.01) { // ignora saltos grandes (ex.: rádio a dormir)
                if (!Double.isNaN(Obd.fuelRateLh)) litres += Obd.fuelRateLh * dtH;
                if (!Double.isNaN(Obd.speed)) km += Obd.speed * dtH;
            }
            if (now - lastFlush > 10000) {
                Obd.addTotals(this, litres, km);
                litres = 0;
                km = 0;
                lastFlush = now;
            }

            // Ignição desligada a meio?
            if (Double.isNaN(Obd.rpm)) {
                if (++noRpm > 8) {
                    setState(Obd.State.NO_CAR, "o motor deixou de responder");
                    Obd.changed();
                    sleep(5000);
                    continue;
                }
            } else {
                noRpm = 0;
                if (Obd.state != Obd.State.CONNECTED) setState(Obd.State.CONNECTED, "");
            }
            Obd.changed();
            sleep(120);
        }
        Obd.addTotals(this, litres, km);
    }

    private double baroKpa = Double.NaN;

    private interface Formula {
        double apply(int[] b);
    }

    private double value(String p, Formula f) throws IOException {
        String r = cmd("01" + p, 1500);
        int[] b = pid(r, p);
        if (b == null || b.length == 0) return Double.NaN;
        try {
            return f.apply(b);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private void readVoltage() throws IOException {
        String r = cmd("ATRV", 1500);
        if (r == null) return;
        try {
            Obd.voltage = Double.parseDouble(r.replaceAll("[^0-9.]", ""));
        } catch (Exception ignored) {
        }
    }

    private void addSupported(int base, int[] b) {
        if (b == null) return;
        for (int i = 0; i < Math.min(4, b.length); i++) {
            for (int bit = 0; bit < 8; bit++) {
                if ((b[i] & (0x80 >> bit)) != 0) {
                    supportedPids.add(String.format(Locale.ROOT, "%02X", base + i * 8 + bit + 1));
                }
            }
        }
    }

    private void readVin() {
        try {
            String r = cmd("0902", 4000);
            if (r == null || r.contains("NO DATA") || r.contains("NODATA")) return;
            StringBuilder hex = new StringBuilder();
            for (String line : r.split("[\\r\\n]+")) {
                line = clean(line);
                if (line.matches("^[0-9A-F]:.*")) line = line.substring(2);
                else if (line.matches("^[0-9A-F]{3}$")) continue;
                hex.append(line);
            }
            String h = hex.toString();
            int at = h.indexOf("4902");
            if (at < 0) return;
            StringBuilder vin = new StringBuilder();
            for (int i = at + 4; i + 1 < h.length(); i += 2) {
                int c = Integer.parseInt(h.substring(i, i + 2), 16);
                if (c >= '0' && c <= 'Z' && Character.isLetterOrDigit(c)) vin.append((char) c);
            }
            String v = vin.toString();
            Obd.vin = v.length() > 17 ? v.substring(v.length() - 17) : v;
        } catch (Exception ignored) {
        }
    }

    private void readMilAndCodes(boolean force) throws IOException {
        if (Obd.simulating()) return; // durante o teste simulado não mexe nos erros mostrados
        int[] b = pid(cmd("0101", 2000), "01");
        boolean mil = false;
        int count = 0;
        if (b != null && b.length > 0) {
            mil = (b[0] & 0x80) != 0;
            count = b[0] & 0x7F;
        }
        boolean changed = mil != Obd.mil || count != Obd.dtcCount;
        Obd.mil = mil;
        Obd.dtcCount = count;
        if (force || changed || count > 0 && Obd.codes.isEmpty()) {
            Obd.codes = codes(cmd("03", 5000), "43");
            Obd.pendingCodes = codes(cmd("07", 5000), "47");
            Obd.codesReadAt = System.currentTimeMillis();
            Obd.log("Luz do motor: " + (mil ? "ACESA" : "apagada") + " · erros: " + Obd.codes
                    + " · pendentes: " + Obd.pendingCodes);
            checkAlert();
        }
        Obd.changed();
    }

    /** Avisa (notificação + janela no ecrã inicial) quando aparecem erros novos. */
    private void checkAlert() {
        List<String> now = new ArrayList<>(Obd.codes);
        if (now.isEmpty() && !Obd.mil) return;
        Set<String> acked = new HashSet<>(Arrays.asList(
                Obd.prefs(this).getString("obd_ack", "").split(",")));
        boolean isNew = Obd.mil && !acked.contains("MIL");
        for (String c : now) if (!acked.contains(c)) isNew = true;
        if (!isNew) return;
        Obd.alertPending = true;
        CloudSync.status(this, null, true);
        StringBuilder text = new StringBuilder();
        for (String c : now) {
            if (text.length() > 0) text.append(" · ");
            text.append(c).append(' ').append(Dtc.info(c).text);
        }
        if (text.length() == 0) text.append("O carro acendeu a luz do motor.");
        try {
            Intent open = new Intent(this, EngineActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "obd_alert") : new Notification.Builder(this);
            Notification n = nb.setSmallIcon(R.drawable.ic_car)
                    .setContentTitle("⚠ Luz do motor acesa")
                    .setContentText(text.toString())
                    .setStyle(new Notification.BigTextStyle().bigText(text.toString()))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND)
                    .build();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ALERT_ID, n);
        } catch (Exception ignored) {
        }
    }

    // ---------- Conversa com o ELM327 ----------

    /** Envia um comando e devolve a resposta (sem o ">"), ou null se não respondeu a tempo. */
    private String cmd(String c, long timeoutMs) throws IOException {
        if (out == null || in == null) throw new IOException("sem ligação");
        while (in.available() > 0) {
            //noinspection ResultOfMethodCallIgnored
            in.read(buf);
        }
        out.write((c + "\r").getBytes("US-ASCII"));
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        boolean done = false;
        while (!done && System.currentTimeMillis() < end && !stopped) {
            int n = in.available();
            if (n > 0) {
                int r = in.read(buf, 0, Math.min(n, buf.length));
                for (int i = 0; i < r; i++) {
                    char ch = (char) (buf[i] & 0xFF);
                    if (ch == '>') {
                        done = true;
                        break;
                    }
                    if (ch != 0) sb.append(ch);
                }
            } else {
                sleep(8);
            }
        }
        String resp = sb.toString().trim();
        boolean quiet = c.length() == 4 && c.startsWith("01") && !c.equals("0100") && !c.equals("0101");
        if (!quiet || !done) Obd.log("→ " + c + "   ← " + (done ? resp.replace('\r', ' ').replace('\n', ' ') : "(sem resposta)"));
        if (!done) {
            if (++timeouts >= 4) throw new IOException("o adaptador deixou de responder");
            return null;
        }
        timeouts = 0;
        return resp;
    }

    private static String clean(String r) {
        return r == null ? "" : r.replace(" ", "").replace("SEARCHING...", "").toUpperCase(Locale.ROOT).trim();
    }

    /** Bytes de dados da resposta a "01xx" (sem o 41 xx), ou null. */
    private static int[] pid(String resp, String p) {
        if (resp == null) return null;
        String want = "41" + p.toUpperCase(Locale.ROOT);
        for (String line : resp.split("[\\r\\n]+")) {
            String l = clean(line);
            int at = l.indexOf(want);
            if (at < 0) continue;
            return hex(l.substring(at + want.length()));
        }
        return null;
    }

    private static int[] hex(String s) {
        s = s.replaceAll("[^0-9A-Fa-f]", "");
        int[] out = new int[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /** Lê a resposta a "03" (erros) ou "07" (pendentes) → lista de códigos tipo "P0401". */
    private List<String> codes(String resp, String header) {
        List<String> found = new ArrayList<>();
        if (resp == null) return found;
        List<String> messages = new ArrayList<>();
        StringBuilder multi = null;
        for (String raw : resp.split("[\\r\\n]+")) {
            String l = clean(raw);
            if (l.isEmpty() || l.contains("NODATA")) continue;
            if (l.matches("^[0-9A-F]{3}$")) {
                if (multi != null) messages.add(multi.toString());
                multi = new StringBuilder();
            } else if (l.matches("^[0-9A-F]:.*")) {
                if (multi == null) multi = new StringBuilder();
                multi.append(l.substring(2));
            } else {
                messages.add(l);
            }
        }
        if (multi != null) messages.add(multi.toString());
        for (String m : messages) {
            int at = m.indexOf(header);
            if (at < 0) continue;
            int[] b = hex(m.substring(at + 2));
            int start = 0, count = Integer.MAX_VALUE;
            if (can && b.length > 0) { // em CAN o primeiro byte é o número de códigos
                count = b[0];
                start = 1;
            }
            int got = 0;
            for (int i = start; i + 1 < b.length && got < count; i += 2) {
                if (b[i] == 0 && b[i + 1] == 0) continue;
                String code = Dtc.decode(b[i], b[i + 1]);
                got++;
                if (!found.contains(code)) found.add(code);
            }
        }
        return found;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
