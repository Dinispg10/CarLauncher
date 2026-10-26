package pt.meucarro.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mapa de exploração: guarda todas as estradas por onde o carro passou (para sempre),
 * conta os km de estradas DIFERENTES e os concelhos visitados, e celebra cada concelho novo.
 */
public final class Explore {

    public interface Listener {
        void onExploreChanged();

        void onNewConcelho(String name, String district, int count);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final int SEG = 20_000_000; // marca "início de troço" na latitude guardada
    private static final double CELL = 40; // quadrícula de ~40 m (em 1e-5 graus)

    private static Set<Long> cells;
    private static double lastLat = Double.NaN, lastLon = Double.NaN;
    private static long lastGrid = Long.MIN_VALUE;

    private Explore() {
    }

    public static void addListener(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    private static File trackFile(Context c) {
        return new File(c.getFilesDir(), "explore_track.bin");
    }

    // ---------- Números ----------

    public static double uniqueKm(Context c) {
        return prefs(c).getFloat("explore_km", 0f);
    }

    /** Concelhos visitados ("Nome|Distrito"), pela ordem em que foram descobertos. */
    public static Set<String> visited(Context c) {
        String s = prefs(c).getString("explore_conc", "");
        Set<String> out = new LinkedHashSet<>();
        if (!s.isEmpty()) out.addAll(Arrays.asList(s.split("\n")));
        return out;
    }

    public static String lastNew(Context c) {
        return prefs(c).getString("explore_last", "");
    }

    // ---------- Registo ----------

    /** Prepara (lê o que já existe; da primeira vez, junta os percursos das viagens já guardadas). */
    public static void init(Context context) {
        Context app = context.getApplicationContext();
        EXEC.execute(() -> ensureLoaded(app));
    }

    /** Ponto novo do GPS (chamado pelo registo de viagens, ~a cada 20 m). */
    public static void addPoint(Context context, double lat, double lon, boolean newSegment) {
        Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            ensureLoaded(app);
            process(app, lat, lon, newSegment, true);
            changed();
        });
    }

    private static void ensureLoaded(Context c) {
        if (cells != null) return;
        cells = new HashSet<>();
        File f = trackFile(c);
        if (f.exists()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                while (true) {
                    int la = in.readInt(), lo = in.readInt();
                    if (la > SEG / 2) la -= SEG;
                    cells.add(cell(la / 1e5, lo / 1e5));
                }
            } catch (EOFException ignored) {
            } catch (Exception ignored) {
            }
        }
        // Primeira vez: aproveita os percursos das viagens já guardadas (sem festa)
        if (!prefs(c).getBoolean("explore_backfilled", false)) {
            List<File> files = new ArrayList<>();
            File[] routes = new File(c.getFilesDir(), "routes").listFiles();
            if (routes != null) files.addAll(Arrays.asList(routes));
            java.util.Collections.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            File cur = new File(c.getFilesDir(), "route_current.txt");
            if (cur.exists()) files.add(cur);
            for (File rf : files) {
                boolean first = true;
                try (BufferedReader r = new BufferedReader(new FileReader(rf))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String[] p = line.split(",");
                        if (p.length < 2) continue;
                        process(c, Integer.parseInt(p[0]) / 1e5, Integer.parseInt(p[1]) / 1e5, first, false);
                        first = false;
                    }
                } catch (Exception ignored) {
                }
            }
            prefs(c).edit().putBoolean("explore_backfilled", true).apply();
            changed();
        }
    }

    private static long cell(double lat, double lon) {
        long a = Math.round(lat * 1e5 / CELL), b = Math.round(lon * 1e5 / CELL);
        return (a << 32) ^ (b & 0xFFFFFFFFL);
    }

    private static void process(Context c, double lat, double lon, boolean newSegment, boolean celebrate) {
        boolean isNew = cells.add(cell(lat, lon));
        SharedPreferences p = prefs(c);
        if (isNew && !newSegment && !Double.isNaN(lastLat)) {
            double d = meters(lastLat, lastLon, lat, lon);
            if (d < 1000) p.edit().putFloat("explore_km", (float) (p.getFloat("explore_km", 0f) + d / 1000.0)).apply();
        }
        lastLat = lat;
        lastLon = lon;
        // Guarda o ponto (para desenhar o mapa)
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(trackFile(c), true))) {
            int la = (int) Math.round(lat * 1e5), lo = (int) Math.round(lon * 1e5);
            out.writeInt(newSegment ? la + SEG : la);
            out.writeInt(lo);
        } catch (Exception ignored) {
        }
        // Concelho: só verifica quando muda a quadrícula de ~1 km
        long grid = (Math.round(lat * 100) << 32) ^ (Math.round(lon * 100) & 0xFFFFFFFFL);
        if (grid == lastGrid && !newSegment) return;
        lastGrid = grid;
        Concelhos.Concelho k = Concelhos.find(c, lat, lon);
        if (k == null) return;
        Set<String> seen = visited(c);
        if (seen.contains(k.key())) return;
        seen.add(k.key());
        StringBuilder sb = new StringBuilder();
        for (String s : seen) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        p.edit().putString("explore_conc", sb.toString()).putString("explore_last", k.name).apply();
        if (celebrate) {
            int count = seen.size();
            AudioLog.add("Exploração: concelho novo " + k.name + " (" + count + "/" + Concelhos.TOTAL + ")");
            notifyNew(c, k, count);
            CloudSync.explore(c);
            MAIN.post(() -> {
                for (Listener l : new ArrayList<>(LISTENERS)) l.onNewConcelho(k.name, k.district, count);
            });
        }
    }

    private static void changed() {
        MAIN.post(() -> {
            for (Listener l : new ArrayList<>(LISTENERS)) l.onExploreChanged();
        });
    }

    private static double meters(double la1, double lo1, double la2, double lo2) {
        double r = 6371000, p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = p2 - p1, dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static void notifyNew(Context c, Concelhos.Concelho k, int count) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("explore", "Concelhos novos",
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            PendingIntent pi = PendingIntent.getActivity(c, 3,
                    new Intent(c, ExploreActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, "explore") : new Notification.Builder(c);
            nm.notify(46, b.setSmallIcon(R.drawable.ic_maps)
                    .setContentTitle("🎉 Concelho novo: " + k.name)
                    .setContentText(count + " de " + Concelhos.TOTAL + " concelhos visitados com o Golf")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) {
        }
    }

    // ---------- Leitura para o mapa ----------

    public interface TrackCallback {
        /** Pontos em coordenadas do mundo (Web Mercator, zoom 0); NaN em x marca início de troço. */
        void onTrack(double[] x, double[] y);
    }

    /** Lê todas as estradas percorridas (em segundo plano) e entrega-as no fio principal. */
    public static void loadTrack(Context context, TrackCallback cb) {
        Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            ensureLoaded(app);
            File f = trackFile(app);
            int n = (int) (f.length() / 8);
            double[] xs = new double[n * 2], ys = new double[n * 2];
            int k = 0;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                for (int i = 0; i < n; i++) {
                    int la = in.readInt(), lo = in.readInt();
                    if (la > SEG / 2) {
                        la -= SEG;
                        xs[k] = Double.NaN;
                        ys[k++] = Double.NaN;
                    }
                    xs[k] = MapTiles.wx(lo / 1e5);
                    ys[k++] = MapTiles.wy(la / 1e5);
                }
            } catch (Exception ignored) {
            }
            final double[] fx = Arrays.copyOf(xs, k), fy = Arrays.copyOf(ys, k);
            MAIN.post(() -> cb.onTrack(fx, fy));
        });
    }
}
