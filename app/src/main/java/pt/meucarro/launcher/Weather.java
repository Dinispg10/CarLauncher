package pt.meucarro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Tempo atual pelo serviço gratuito Open-Meteo (sem conta nem chave),
 * a partir da localização do GPS. Guarda o último valor nas preferências.
 */
public final class Weather {

    public interface Callback {
        void onDone(boolean ok);
    }

    public static final long REFRESH_MS = 30 * 60 * 1000L;   // atualiza de 30 em 30 min
    public static final long MAX_AGE_MS = 3 * 60 * 60 * 1000L; // mais velho que 3 h: esconde

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy;

    private Weather() {
    }

    public static void fetch(Context context, double lat, double lon, Callback callback) {
        if (busy) return;
        busy = true;
        final SharedPreferences prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        new Thread(() -> {
            boolean ok = false;
            HttpURLConnection conn = null;
            try {
                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                                + "&current=temperature_2m,weather_code,is_day",
                        lat, lon);
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() == 200) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = conn.getInputStream()) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    }
                    JSONObject current = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
                    prefs.edit()
                            .putFloat("w_temp", (float) current.getDouble("temperature_2m"))
                            .putInt("w_code", current.getInt("weather_code"))
                            .putBoolean("w_day", current.optInt("is_day", 1) == 1)
                            .putLong("w_time", System.currentTimeMillis())
                            .apply();
                    ok = true;
                }
            } catch (Exception ignored) {
                // sem internet: fica o último valor
            } finally {
                if (conn != null) conn.disconnect();
            }
            final boolean result = ok;
            MAIN.post(() -> {
                busy = false;
                callback.onDone(result);
            });
        }).start();
    }

    /** Ícone para o código de tempo da WMO usado pelo Open-Meteo. */
    public static int iconFor(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_w_sun : R.drawable.ic_w_moon;
        if (code == 1 || code == 2) return day ? R.drawable.ic_w_partly : R.drawable.ic_w_cloud;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if (code >= 95) return R.drawable.ic_w_storm;
        if (code >= 51) return R.drawable.ic_w_rain; // chuvisco, chuva, aguaceiros
        return R.drawable.ic_w_cloud;
    }
}
