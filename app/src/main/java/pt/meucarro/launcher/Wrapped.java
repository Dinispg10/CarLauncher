package pt.meucarro.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Golf Wrapped": estatísticas de cada mês (e de cada ano), guardadas em files/wrapped/AAAA-MM.json.
 * km (GPS e conta-quilómetros), litros, tempo ao volante, km por hora do dia, viagem mais longa,
 * melhor consumo e concelhos novos.
 */
public final class Wrapped {

    private Wrapped() {
    }

    public static final String[] MONTHS = {"Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho",
            "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"};

    private static String curKey;
    private static JSONObject cur;
    private static long lastSave;

    public static String monthKey(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return String.format(Locale.ROOT, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "wrapped");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Mês em curso (em memória; gravado de minuto a minuto). */
    private static synchronized JSONObject current(Context c) {
        String key = monthKey(System.currentTimeMillis());
        if (!key.equals(curKey)) {
            if (cur != null) save(c);
            curKey = key;
            cur = load(c, key);
            if (cur == null) cur = new JSONObject();
        }
        return cur;
    }

    static JSONObject load(Context c, String key) {
        File f = new File(dir(c), key + ".json");
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized void save(Context c) {
        if (cur == null || curKey == null) return;
        lastSave = System.currentTimeMillis();
        try (FileOutputStream out = new FileOutputStream(new File(dir(c), curKey + ".json"))) {
            out.write(cur.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static void maybeSave(Context c) {
        if (System.currentTimeMillis() - lastSave > 60000) save(c);
    }

    // ---------- Registo (chamado pelos serviços) ----------

    /** Distância andada (GPS), com a hora do dia e o tempo ao volante. */
    public static synchronized void addKm(Context c, double km, long movingMs) {
        try {
            JSONObject m = current(c);
            if (!m.has("since")) m.put("since", System.currentTimeMillis());
            m.put("km", m.optDouble("km", 0) + km);
            m.put("ms", m.optLong("ms", 0) + movingMs);
            JSONArray h = m.optJSONArray("h");
            if (h == null) {
                h = new JSONArray();
                for (int i = 0; i < 24; i++) h.put(0);
                m.put("h", h);
            }
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            h.put(hour, h.optDouble(hour, 0) + km);
            maybeSave(c);
        } catch (Exception ignored) {
        }
    }

    /** Gasóleo gasto e km contados pelo adaptador (para o consumo médio do mês). */
    public static synchronized void addFuel(Context c, double litres, double km) {
        try {
            JSONObject m = current(c);
            m.put("l", m.optDouble("l", 0) + litres);
            m.put("okm", m.optDouble("okm", 0) + km);
            maybeSave(c);
        } catch (Exception ignored) {
        }
    }

    /** Conta-quilómetros do carro (caixa CAN): primeiro e último valor do mês. */
    public static synchronized void odometer(Context c, double odo) {
        if (!(odo > 1000)) return;
        try {
            JSONObject m = current(c);
            if (!m.has("odo0")) m.put("odo0", odo);
            m.put("odo1", odo);
        } catch (Exception ignored) {
        }
    }

    public static synchronized void newConcelho(Context c, String name) {
        try {
            JSONObject m = current(c);
            JSONArray a = m.optJSONArray("conc");
            if (a == null) m.put("conc", a = new JSONArray());
            for (int i = 0; i < a.length(); i++) if (name.equals(a.optString(i))) return;
            a.put(name);
            save(c);
        } catch (Exception ignored) {
        }
    }

    /** Uma viagem acabou: conta-a e vê se é a mais longa ou a de melhor consumo do mês. */
    public static synchronized void onTrip(Context c, long start, double km, long durMs, double l100, List<int[]> route) {
        if (km < 0.5) return;
        try {
            JSONObject m = monthFor(c, start);
            m.put("trips", m.optInt("trips", 0) + 1);
            JSONObject longest = m.optJSONObject("longest");
            if (longest == null || km > longest.optDouble("km", 0)) {
                JSONObject t = new JSONObject();
                t.put("s", start).put("km", km).put("d", durMs);
                if (!Double.isNaN(l100)) t.put("l100", l100);
                places(c, route, t);
                JSONArray r = new JSONArray();
                int step = Math.max(1, route.size() / 150);
                for (int i = 0; i < route.size(); i += step) r.put(route.get(i)[0]).put(route.get(i)[1]);
                if (!route.isEmpty()) r.put(route.get(route.size() - 1)[0]).put(route.get(route.size() - 1)[1]);
                t.put("r", r);
                m.put("longest", t);
            }
            JSONObject best = m.optJSONObject("best");
            if (!Double.isNaN(l100) && km >= 15 && l100 > 2 && (best == null || l100 < best.optDouble("l100", 99))) {
                JSONObject t = new JSONObject();
                t.put("s", start).put("km", km).put("l100", l100);
                places(c, route, t);
                m.put("best", t);
            }
            saveMonth(c, start, m);
        } catch (Exception ignored) {
        }
    }

    /** De onde para onde: concelho do início e do ponto mais longe do início. */
    private static void places(Context c, List<int[]> route, JSONObject t) throws Exception {
        if (route.isEmpty()) return;
        int[] a = route.get(0);
        int[] far = a;
        double best = -1;
        for (int[] p : route) {
            double dx = (p[1] - a[1]) * Math.cos(Math.toRadians(a[0] / 1e5)), dy = p[0] - a[0];
            double d = dx * dx + dy * dy;
            if (d > best) {
                best = d;
                far = p;
            }
        }
        Concelhos.Concelho k0 = Concelhos.find(c, a[0] / 1e5, a[1] / 1e5);
        Concelhos.Concelho k1 = Concelhos.find(c, far[0] / 1e5, far[1] / 1e5);
        if (k0 != null) t.put("from", k0.name);
        if (k1 != null) t.put("to", k1.name);
    }

    private static JSONObject monthFor(Context c, long t) {
        String key = monthKey(t);
        if (key.equals(monthKey(System.currentTimeMillis()))) return current(c);
        JSONObject m = load(c, key);
        return m != null ? m : new JSONObject();
    }

    private static void saveMonth(Context c, long t, JSONObject m) {
        String key = monthKey(t);
        if (key.equals(curKey)) {
            save(c);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir(c), key + ".json"))) {
            out.write(m.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** Primeira vez: aproveita as últimas viagens guardadas (as do mês que já passaram). */
    public static void backfill(Context c) {
        SharedPreferences p = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        if (p.getBoolean("wrapped_v1", false)) return;
        p.edit().putBoolean("wrapped_v1", true).apply();
        for (TripService.Trip t : TripService.history(c)) {
            onTrip(c, t.startMs, t.km, t.durationMs, t.l100, TripService.route(c, t.startMs));
            try {
                JSONObject m = monthFor(c, t.startMs);
                m.put("km", m.optDouble("km", 0) + t.km);
                m.put("ms", m.optLong("ms", 0) + t.durationMs);
                JSONArray h = m.optJSONArray("h");
                if (h == null) {
                    h = new JSONArray();
                    for (int i = 0; i < 24; i++) h.put(0);
                    m.put("h", h);
                }
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(t.startMs);
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                h.put(hour, h.optDouble(hour, 0) + t.km);
                if (!m.has("since") || m.optLong("since") > t.startMs) m.put("since", t.startMs);
                saveMonth(c, t.startMs, m);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- Leitura (para o ecrã) ----------

    /** Meses com dados, do mais recente para o mais antigo. */
    public static List<String> months(Context c) {
        save(c);
        List<String> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (n.matches("\\d{4}-\\d{2}\\.json")) out.add(n.substring(0, 7));
        }
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }

    public static String title(String key) {
        if (key.length() == 4) return key;
        return MONTHS[Integer.parseInt(key.substring(5, 7)) - 1] + " " + key.substring(0, 4);
    }

    /**
     * Dados prontos para os ecrãs. key = "AAAA-MM" (um mês) ou "AAAA" (o ano todo).
     * Devolve null se não houver nada.
     */
    public static JSONObject summary(Context c, String key) {
        save(c);
        try {
            List<JSONObject> parts = new ArrayList<>();
            if (key.length() == 4) {
                for (int i = 1; i <= 12; i++) {
                    JSONObject m = load(c, String.format(Locale.ROOT, "%s-%02d", key, i));
                    if (m != null) parts.add(m);
                }
            } else {
                JSONObject m = load(c, key);
                if (m != null) parts.add(m);
            }
            if (parts.isEmpty()) return null;
            double km = 0, l = 0, okm = 0;
            long ms = 0;
            int trips = 0;
            double[] h = new double[24];
            JSONObject longest = null, best = null;
            JSONArray conc = new JSONArray();
            long since = Long.MAX_VALUE;
            for (JSONObject m : parts) {
                double gps = m.optDouble("km", 0);
                double odo = m.has("odo0") ? m.optDouble("odo1", 0) - m.optDouble("odo0", 0) : 0;
                km += Math.max(gps, odo);
                l += m.optDouble("l", 0);
                okm += m.optDouble("okm", 0);
                ms += m.optLong("ms", 0);
                trips += m.optInt("trips", 0);
                since = Math.min(since, m.optLong("since", Long.MAX_VALUE));
                JSONArray hh = m.optJSONArray("h");
                if (hh != null) for (int i = 0; i < 24; i++) h[i] += hh.optDouble(i, 0);
                JSONObject lo = m.optJSONObject("longest");
                if (lo != null && (longest == null || lo.optDouble("km") > longest.optDouble("km"))) longest = lo;
                JSONObject b = m.optJSONObject("best");
                if (b != null && (best == null || b.optDouble("l100") < best.optDouble("l100"))) best = b;
                JSONArray cc = m.optJSONArray("conc");
                if (cc != null) for (int i = 0; i < cc.length(); i++) conc.put(cc.optString(i));
            }
            JSONObject o = new JSONObject();
            o.put("key", key).put("title", key.length() == 4 ? key : MONTHS[Integer.parseInt(key.substring(5, 7)) - 1]);
            o.put("year", key.substring(0, 4)).put("isYear", key.length() == 4);
            o.put("name", c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("driver_name", "Dinis"));
            o.put("km", Math.round(km));
            o.put("litres", Math.round(l));
            o.put("avg", okm > 30 && l > 0 ? Math.round(l / okm * 1000) / 10.0 : JSONObject.NULL);
            o.put("hours", Math.round(ms / 360000.0) / 10.0);
            o.put("trips", trips);
            JSONArray ha = new JSONArray();
            for (double v : h) ha.put(Math.round(v * 10) / 10.0);
            o.put("h", ha);
            o.put("longest", longest == null ? JSONObject.NULL : longest);
            o.put("best", best == null ? JSONObject.NULL : best);
            o.put("conc", conc);
            java.util.Set<String> vis = Explore.visited(c);
            o.put("visited", vis.size());
            JSONArray vn = new JSONArray();
            for (String v : vis) vn.put(v.split("\\|")[0]);
            o.put("vis", vn);
            o.put("total", Concelhos.TOTAL);
            o.put("since", since == Long.MAX_VALUE ? 0 : since);
            // Mês anterior (para comparar)
            if (key.length() == 7) {
                int y = Integer.parseInt(key.substring(0, 4)), mo = Integer.parseInt(key.substring(5, 7));
                String prevKey = mo == 1 ? String.format(Locale.ROOT, "%04d-12", y - 1)
                        : String.format(Locale.ROOT, "%04d-%02d", y, mo - 1);
                JSONObject prev = load(c, prevKey);
                if (prev != null) {
                    double pk = Math.max(prev.optDouble("km", 0),
                            prev.has("odo0") ? prev.optDouble("odo1", 0) - prev.optDouble("odo0", 0) : 0);
                    if (pk > 50) o.put("prevKm", Math.round(pk)).put("prevName", MONTHS[(mo + 10) % 12].toLowerCase(Locale.ROOT));
                }
            }
            o.put("partial", key.equals(monthKey(System.currentTimeMillis()))
                    || key.equals(monthKey(System.currentTimeMillis()).substring(0, 4)));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Resumo por mostrar (no 1.º dia do mês, o mês anterior; em janeiro, o ano). Ou null. */
    public static String pending(Context c) {
        Calendar cal = Calendar.getInstance();
        int y = cal.get(Calendar.YEAR), mo = cal.get(Calendar.MONTH) + 1;
        String key = mo == 1 ? String.valueOf(y - 1) : String.format(Locale.ROOT, "%04d-%02d", y, mo - 1);
        if (cal.get(Calendar.DAY_OF_MONTH) > 10) return null; // só nos primeiros dias do mês
        SharedPreferences p = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        if (p.getBoolean("wrap_seen_" + key, false)) return null;
        JSONObject s = summary(c, key);
        return s != null && s.optLong("km") >= 20 ? key : null;
    }

    public static void markSeen(Context c, String key) {
        c.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putBoolean("wrap_seen_" + key, true).apply();
    }
}
