package pt.meucarro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.location.Location;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.nayuki.qrcodegen.QrCode;

/**
 * Envia o estado do carro para a "app Golf" do iPhone (página web), através de uma base de dados
 * Firebase. Os dados ficam numa pasta com um código secreto aleatório; só quem tem o link (QR) os vê.
 * Só envia com internet (hotspot); sem internet, falha em silêncio e tenta da próxima vez.
 */
public final class CloudSync {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static long lastStatus;
    private static Location lastLoc;

    private CloudSync() {
    }

    public static boolean configured() {
        return BuildConfig.CLOUD_URL != null && BuildConfig.CLOUD_URL.startsWith("https://");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    /** Código secreto do carro (criado uma vez, ao acaso). */
    public static String key(Context c) {
        String k = prefs(c).getString("cloud_key", null);
        if (k == null) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            k = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            prefs(c).edit().putString("cloud_key", k).apply();
        }
        return k;
    }

    /** Link da página no iPhone (GitHub Pages do mesmo repositório das atualizações). */
    public static String link(Context c) {
        String repo = BuildConfig.UPDATE_REPO; // "Dono/Repositório"
        int slash = repo.indexOf('/');
        String owner = slash > 0 ? repo.substring(0, slash).toLowerCase(Locale.ROOT) : "dinispg10";
        String name = slash > 0 ? repo.substring(slash + 1) : "CarLauncher";
        return "https://" + owner + ".github.io/" + name + "/#k=" + key(c);
    }

    /** Código QR do link, para apontar a câmara do iPhone. */
    public static Bitmap qr(Context c, int scale) {
        QrCode q = QrCode.encodeText(link(c), QrCode.Ecc.MEDIUM);
        int border = 3, size = (q.size + border * 2) * scale;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        b.eraseColor(0xFFFFFFFF);
        for (int y = 0; y < q.size; y++) {
            for (int x = 0; x < q.size; x++) {
                if (!q.getModule(x, y)) continue;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        b.setPixel((x + border) * scale + dx, (y + border) * scale + dy, 0xFF0B0D10);
                    }
                }
            }
        }
        return b;
    }

    private static void put(Context c, String path, String json) {
        if (!configured()) return;
        String url = BuildConfig.CLOUD_URL.replaceAll("/+$", "") + "/cars/" + key(c) + "/" + path + ".json";
        EXEC.execute(() -> {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                con.setRequestMethod("PUT");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = con.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                con.getResponseCode();
            } catch (Exception ignored) {
                // sem internet: fica para a próxima
            } finally {
                if (con != null) con.disconnect();
            }
        });
    }

    // ---------- O que se envia ----------

    /** Onde está o carro, se está a andar, e o motor (de minuto a minuto e quando pára). */
    public static void status(Context c, Location loc, boolean force) {
        long now = System.currentTimeMillis();
        if (!configured() || (!force && now - lastStatus < 55000)) return;
        lastStatus = now;
        if (loc != null) lastLoc = loc;
        else loc = lastLoc;
        try {
            JSONObject s = new JSONObject();
            s.put("t", now);
            if (loc != null) {
                s.put("lat", loc.getLatitude());
                s.put("lon", loc.getLongitude());
                float kmh = loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f;
                s.put("spd", Math.round(kmh));
                s.put("moving", kmh > 3);
            }
            JSONObject e = new JSONObject();
            e.put("state", Obd.state.name());
            putNum(e, "coolant", Obd.coolant);
            putNum(e, "volt", Obd.voltage);
            putNum(e, "rpm", Obd.rpm);
            e.put("mil", Obd.mil);
            JSONArray codes = new JSONArray();
            for (String code : Obd.codes) {
                JSONObject o = new JSONObject();
                o.put("c", code);
                o.put("d", Dtc.info(code).text);
                codes.put(o);
            }
            e.put("codes", codes);
            e.put("checked", Obd.codesReadAt);
            if (CanBox.fresh()) putNum(e, "volt", CanBox.batteryV);
            s.put("engine", e);
            // Painel do Golf (caixa CAN): litros, km totais e temperatura exterior
            if (!Double.isNaN(CanBox.fuelL)) {
                JSONObject car = new JSONObject();
                putNum(car, "fuel", CanBox.fuelL);
                putNum(car, "odo", CanBox.odometerKm);
                putNum(car, "out", CanBox.outsideC);
                car.put("at", CanBox.carDataAt);
                s.put("car", car);
            }
            TripService.Trip t = TripService.current(c);
            if (t != null) {
                JSONObject tr = new JSONObject();
                tr.put("s", t.startMs);
                tr.put("km", Math.round(t.km * 10) / 10.0);
                tr.put("d", t.durationMs);
                putNum(tr, "l", t.l100);
                s.put("trip", tr);
            }
            put(c, "status", s.toString());
        } catch (Exception ignored) {
        }
    }

    /** Últimas viagens, cada uma com o percurso simplificado (até 250 pontos). */
    public static void trips(Context c) {
        if (!configured()) return;
        try {
            JSONArray arr = new JSONArray();
            for (TripService.Trip t : TripService.history(c)) {
                JSONObject o = new JSONObject();
                o.put("s", t.startMs);
                o.put("km", Math.round(t.km * 10) / 10.0);
                o.put("d", t.durationMs);
                putNum(o, "l", t.l100);
                List<int[]> route = TripService.route(c, t.startMs);
                JSONArray pts = new JSONArray();
                int step = Math.max(1, route.size() / 250);
                for (int i = 0; i < route.size(); i += step) {
                    int[] p = route.get(i);
                    pts.put(p[0]).put(p[1]).put(p[2]);
                }
                o.put("r", pts);
                arr.put(o);
            }
            put(c, "trips", arr.toString());
        } catch (Exception ignored) {
        }
    }

    /** Números da exploração e lista de concelhos visitados. */
    public static void explore(Context c) {
        if (!configured()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("km", Math.round(Explore.uniqueKm(c) * 10) / 10.0);
            o.put("total", Concelhos.TOTAL);
            o.put("last", Explore.lastNew(c));
            JSONArray list = new JSONArray();
            for (String k : Explore.visited(c)) list.put(k);
            o.put("list", list);
            put(c, "explore", o.toString());
        } catch (Exception ignored) {
        }
    }

    private static void putNum(JSONObject o, String k, double v) throws Exception {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) o.put(k, Math.round(v * 10) / 10.0);
    }
}
