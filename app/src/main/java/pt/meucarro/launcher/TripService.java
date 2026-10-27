package pt.meucarro.launcher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Regista a viagem com o GPS, mesmo com outras apps abertas (Waze, Spotify…).
 * Uma viagem nova começa quando o carro esteve desligado/parado sem GPS mais de 10 minutos.
 * Também deixa um "sinal de vida" a cada 30 s, para a app saber quando o carro foi ligado.
 */
public class TripService extends Service {

    public interface Listener {
        void onTripChanged();
    }

    public static final long NEW_TRIP_GAP_MS = 10 * 60 * 1000L;
    private static final long HEARTBEAT_MS = 30 * 1000L;
    private static final long SAVE_MS = 15 * 1000L;
    private static final int NOTIFICATION_ID = 43;
    private static final String CHANNEL = "trip";

    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static TripService running;

    public static void addListener(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static void start(Context context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent intent = new Intent(context, TripService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) {
            // alguns sistemas recusam iniciar serviços em certos momentos; tenta-se no próximo onResume
        }
    }

    // ---------- Leitura dos dados (para o ecrã) ----------

    public static final class Trip {
        public final double km;
        public final long durationMs;
        public final long startMs;
        /** Consumo médio (L/100 km) medido pelo adaptador OBD2, ou NaN se não houver. */
        public double l100 = Double.NaN;

        Trip(double km, long durationMs, long startMs) {
            this.km = km;
            this.durationMs = durationMs;
            this.startMs = startMs;
        }

        public String l100Text() {
            return Double.isNaN(l100) ? "—" : String.format(Locale.forLanguageTag("pt-PT"), "%.1f L", l100);
        }

        public double avgKmh() {
            return durationMs > 60000 ? km / (durationMs / 3600000.0) : 0;
        }

        public String summary() {
            return formatKm(km) + " · " + formatDuration(durationMs) + " · "
                    + Math.round(avgKmh()) + " km/h";
        }
    }

    /** Viagem atual, ou null se ainda não há nenhuma (ou se já acabou). */
    public static Trip current(Context context) {
        SharedPreferences p = prefs(context);
        long start = p.getLong("trip_start", 0);
        long last = p.getLong("trip_last_time", 0);
        if (start == 0 || System.currentTimeMillis() - last > NEW_TRIP_GAP_MS) return null;
        Trip t = new Trip(p.getFloat("trip_km", 0f), last - start, start);
        t.l100 = tripL100(context, p);
        return t;
    }

    /** Consumo da viagem atual com os contadores do adaptador (só conta enquanto esteve ligado). */
    private static double tripL100(Context c, SharedPreferences p) {
        if (!p.contains("trip_fuel0")) return Double.NaN;
        double km = Obd.totalKm(c) - p.getFloat("trip_okm0", 0f);
        double l = Obd.totalFuelL(c) - p.getFloat("trip_fuel0", 0f);
        return km >= 1.0 && l > 0 ? l / km * 100.0 : Double.NaN;
    }

    private static void markObdStart(Context c, SharedPreferences p) {
        p.edit().putFloat("trip_fuel0", (float) Obd.totalFuelL(c))
                .putFloat("trip_okm0", (float) Obd.totalKm(c)).apply();
    }

    public static Trip previous(Context context) {
        SharedPreferences p = prefs(context);
        long start = p.getLong("prev_start", 0);
        if (start == 0) return null;
        return new Trip(p.getFloat("prev_km", 0f), p.getLong("prev_duration", 0), start);
    }

    /** Começa uma viagem nova (a atual passa a ser a "anterior"). */
    public static void reset(Context context) {
        SharedPreferences p = prefs(context);
        if (running != null) running.save();
        archive(context, p);
        p.edit().putLong("trip_start", 0).putFloat("trip_km", 0f).apply();
        markObdStart(context, p);
        if (running != null) {
            running.start = 0;
            running.km = 0;
            running.lastPoint = null;
            running.route.clear();
            running.lastRoutePoint = null;
        }
        notifyListeners();
    }

    public static String formatKm(double km) {
        return km < 10 ? String.format(Locale.forLanguageTag("pt-PT"), "%.1f km", km)
                : Math.round(km) + " km";
    }

    public static String formatDuration(long ms) {
        long min = ms / 60000;
        return min < 60 ? min + " min" : (min / 60) + " h " + String.format(Locale.ROOT, "%02d", min % 60);
    }

    // ---------- Serviço ----------

    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private SharedPreferences p;
    private Location lastPoint;
    private long lastSave;
    private double km;
    private long start;
    private long lastTime;
    /** Percurso da viagem atual: {lat×1e5, lon×1e5, km/h}. */
    private final List<int[]> route = new ArrayList<>();
    private Location lastRoutePoint;
    private boolean wasMoving;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            p.edit().putLong("heartbeat", System.currentTimeMillis()).apply();
            CloudSync.status(TripService.this, lastPoint, false); // app Golf no iPhone
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            onFix(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        p = prefs(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        start = p.getLong("trip_start", 0);
        km = p.getFloat("trip_km", 0f);
        lastTime = p.getLong("trip_last_time", 0);
        if (start != 0) route.addAll(readRoute(currentRouteFile(this)));
        Explore.init(this);
        CanBox.start(this); // escuta a caixa CAN (para os litros do depósito)
        CloudSync.trips(this);
        CloudSync.explore(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Viagem", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_maps)
                .setContentTitle("Launcher do Carro")
                .setContentText("A registar a viagem")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        main.removeCallbacks(heartbeat);
        main.post(heartbeat);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener);
        } catch (SecurityException | IllegalArgumentException e) {
            stopSelf(); // sem GPS ou sem autorização
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(heartbeat);
        try {
            locationManager.removeUpdates(listener);
        } catch (Exception ignored) {
        }
        save();
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onFix(Location location) {
        long now = System.currentTimeMillis();
        if (location.hasAccuracy() && location.getAccuracy() > 35f) return; // ponto pouco preciso

        // Esteve muito tempo sem GPS (carro desligado): fecha a viagem e começa outra
        if (start == 0 || now - lastTime > NEW_TRIP_GAP_MS) {
            if (start != 0) {
                save();
                archive(this, p);
            }
            markObdStart(this, p);
            start = now;
            km = 0;
            lastPoint = null;
            route.clear();
            lastRoutePoint = null;
        }
        if (lastPoint != null) {
            float meters = lastPoint.distanceTo(location);
            float speed = location.hasSpeed() ? location.getSpeed() : 0f;
            // Só conta movimento real: ignora o "tremer" do GPS com o carro parado
            if (speed > 1.0f && meters < 500f) km += meters / 1000.0;
        }
        // Percurso: um ponto a cada ~20 m (máximo 6000 pontos por viagem)
        float kmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
        if (route.size() < 6000 && (lastRoutePoint == null
                || (kmh > 3 && lastRoutePoint.distanceTo(location) >= 20f))) {
            route.add(new int[]{(int) Math.round(location.getLatitude() * 1e5),
                    (int) Math.round(location.getLongitude() * 1e5), Math.round(kmh)});
            // Mapa de exploração: todas as estradas por onde o carro já passou
            Explore.addPoint(this, location.getLatitude(), location.getLongitude(), lastRoutePoint == null);
            lastRoutePoint = location;
        }
        // Parou (ex.: estacionou): envia logo a posição para a app do iPhone
        boolean movingNow = location.hasSpeed() && location.getSpeed() > 3f;
        if (wasMoving && location.hasSpeed() && location.getSpeed() < 1f) CloudSync.status(this, location, true);
        if (lastPoint == null) CloudSync.status(this, location, true);
        CloudSync.status(this, location, false); // a andar: de 4 em 4 s
        if (movingNow) CloudSync.trail(this, start, new ArrayList<>(route));
        if (movingNow) wasMoving = true;
        else if (location.hasSpeed() && location.getSpeed() < 1f) wasMoving = false;
        lastPoint = location;
        lastTime = now;
        if (now - lastSave > SAVE_MS) save();
        notifyListeners();
    }

    private void save() {
        if (p == null || start == 0) return;
        p.edit().putLong("trip_start", start)
                .putFloat("trip_km", (float) km)
                .putLong("trip_last_time", lastTime)
                .apply();
        writeRoute(currentRouteFile(this), route);
        lastSave = System.currentTimeMillis();
    }

    /** Guarda a viagem atual como "anterior" (só se tiver andado alguma coisa). */
    private static final int HISTORY_SIZE = 10;

    private static void archive(Context c, SharedPreferences p) {
        long start = p.getLong("trip_start", 0);
        float tripKm = p.getFloat("trip_km", 0f);
        if (start == 0) return;
        if (tripKm < Math.max(0.2f, minKm(c))) {
            // Viagem curta demais para guardar: deita fora o percurso
            //noinspection ResultOfMethodCallIgnored
            currentRouteFile(c).delete();
            return;
        }
        long duration = p.getLong("trip_last_time", start) - start;
        // Junta ao histórico (mais recente primeiro, no máximo 10)
        JSONArray next = new JSONArray();
        try {
            JSONObject entry = new JSONObject();
            entry.put("s", start);
            entry.put("km", (double) tripKm);
            entry.put("d", duration);
            double l100 = tripL100(c, p);
            if (!Double.isNaN(l100)) entry.put("l", l100);
            next.put(entry);
            JSONArray old = new JSONArray(p.getString("trip_history", "[]"));
            for (int i = 0; i < old.length() && next.length() < HISTORY_SIZE; i++) {
                JSONObject o = old.getJSONObject(i);
                if (o.optLong("s") != start) next.put(o);
            }
        } catch (Exception ignored) {
        }
        // Guarda o percurso com o início da viagem e apaga os de viagens que já saíram da lista
        try {
            File dir = new File(c.getFilesDir(), "routes");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File cur = currentRouteFile(c);
            if (cur.exists()) //noinspection ResultOfMethodCallIgnored
                cur.renameTo(new File(dir, start + ".txt"));
            java.util.Set<String> keep = new java.util.HashSet<>();
            for (int i = 0; i < next.length(); i++) keep.add(next.getJSONObject(i).optLong("s") + ".txt");
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) if (!keep.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Exception ignored) {
        }
        p.edit().putLong("prev_start", start)
                .putFloat("prev_km", tripKm)
                .putLong("prev_duration", duration)
                .putString("trip_history", next.toString())
                .commit();
        CloudSync.trips(c);
    }

    // ---------- Percursos ----------

    private static File currentRouteFile(Context c) {
        return new File(c.getFilesDir(), "route_current.txt");
    }

    /** Percurso de uma viagem (a atual ou uma das últimas), ou lista vazia. */
    public static List<int[]> route(Context c, long tripStart) {
        if (running != null && running.start == tripStart) return new ArrayList<>(running.route);
        File f = new File(new File(c.getFilesDir(), "routes"), tripStart + ".txt");
        if (!f.exists() && prefs(c).getLong("trip_start", 0) == tripStart) f = currentRouteFile(c);
        return readRoute(f);
    }

    private static void writeRoute(File f, List<int[]> pts) {
        StringBuilder sb = new StringBuilder(pts.size() * 20);
        for (int[] pt : pts) sb.append(pt[0]).append(',').append(pt[1]).append(',').append(pt[2]).append('\n');
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(sb.toString().getBytes("US-ASCII"));
        } catch (Exception ignored) {
        }
    }

    private static List<int[]> readRoute(File f) {
        List<int[]> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                out.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Últimas viagens terminadas, da mais recente para a mais antiga. */
    public static List<Trip> history(Context context) {
        List<Trip> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(context).getString("trip_history", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Trip t = new Trip(o.optDouble("km", 0), o.optLong("d", 0), o.optLong("s", 0));
                t.l100 = o.optDouble("l", Double.NaN);
                out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Só guarda na lista as viagens com pelo menos estes km (0 = todas). */
    public static float minKm(Context c) {
        return prefs(c).getFloat("trip_min_km", 10f);
    }

    /** Muda o mínimo e apaga da lista as viagens que já lá estão e são mais curtas. */
    public static void setMinKm(Context c, float km) {
        prefs(c).edit().putFloat("trip_min_km", km).apply();
        for (Trip t : history(c)) if (t.km < km) deleteTrip(c, t.startMs);
        notifyListeners();
    }

    /** Apaga uma viagem da lista (e o percurso dela). */
    public static void deleteTrip(Context c, long tripStart) {
        SharedPreferences p = prefs(c);
        try {
            JSONArray old = new JSONArray(p.getString("trip_history", "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (o.optLong("s") != tripStart) next.put(o);
            }
            p.edit().putString("trip_history", next.toString()).apply();
            if (p.getLong("prev_start", 0) == tripStart) p.edit().remove("prev_start").apply();
        } catch (Exception ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        new File(new File(c.getFilesDir(), "routes"), tripStart + ".txt").delete();
        CloudSync.trips(c);
        notifyListeners();
    }

    public static void clearHistory(Context context) {
        prefs(context).edit().remove("trip_history").remove("prev_start").apply();
        File[] files = new File(context.getFilesDir(), "routes").listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        notifyListeners();
    }

    private static void notifyListeners() {
        for (Listener l : new ArrayList<>(LISTENERS)) l.onTripChanged();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }
}
