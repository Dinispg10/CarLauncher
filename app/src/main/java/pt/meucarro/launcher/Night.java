package pt.meucarro.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Modo noite: escurece o ecrã entre o pôr e o nascer do sol.
 * As horas do sol são calculadas a partir da posição e da data, sem internet.
 */
public final class Night {

    public static final String AUTO = "auto";
    public static final String ON = "on";
    public static final String OFF = "off";

    // Sem posição nenhuma ainda: centro de Portugal (a diferença no país é de minutos)
    private static final double DEFAULT_LAT = 39.6;
    private static final double DEFAULT_LON = -8.0;

    private static final String OVERLAY_TAG = "night_overlay";

    private Night() {
    }

    public static String mode(Context context) {
        return prefs(context).getString("night_mode", AUTO);
    }

    public static void setMode(Context context, String mode) {
        prefs(context).edit().putString("night_mode", mode).apply();
    }

    /** Guarda a última posição do GPS, para o cálculo funcionar logo ao ligar o carro. */
    public static void saveLocation(Context context, Location location) {
        prefs(context).edit()
                .putFloat("loc_lat", (float) location.getLatitude())
                .putFloat("loc_lon", (float) location.getLongitude())
                .apply();
    }

    public static boolean isNight(Context context) {
        String mode = mode(context);
        if (ON.equals(mode)) return true;
        if (OFF.equals(mode)) return false;
        SharedPreferences p = prefs(context);
        double lat = p.contains("loc_lat") ? p.getFloat("loc_lat", 0f) : DEFAULT_LAT;
        double lon = p.contains("loc_lon") ? p.getFloat("loc_lon", 0f) : DEFAULT_LON;
        return isSunDown(lat, lon, System.currentTimeMillis());
    }

    /** Põe ou tira o véu escuro por cima do ecrã. Os toques passam através dele. */
    public static void apply(Activity activity, boolean night) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        View overlay = content.findViewWithTag(OVERLAY_TAG);
        if (overlay == null) {
            overlay = new View(activity);
            overlay.setTag(OVERLAY_TAG);
            overlay.setBackgroundColor(0xFF000000);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            content.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        overlay.bringToFront();
        overlay.animate().alpha(night ? 0.38f : 0f).setDuration(600).start();
        overlay.setVisibility(View.VISIBLE);
    }

    // ---------- Cálculo do nascer e pôr do sol (algoritmo do Almanac for Computers / NOAA) ----------

    static boolean isSunDown(double lat, double lon, long nowMs) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(nowMs);
        int dayOfYear = utc.get(Calendar.DAY_OF_YEAR);
        double nowHour = utc.get(Calendar.HOUR_OF_DAY) + utc.get(Calendar.MINUTE) / 60.0;

        Double rise = sunTimeUtc(dayOfYear, lat, lon, true);
        Double set = sunTimeUtc(dayOfYear, lat, lon, false);
        if (rise == null || set == null) {
            // Dia ou noite polar: decide pela estação (não acontece em Portugal)
            return Math.abs(lat) > 60 && ((lat > 0) == (dayOfYear < 80 || dayOfYear > 265));
        }
        boolean day = rise < set
                ? nowHour >= rise && nowHour < set
                : nowHour >= rise || nowHour < set; // o intervalo passa pela meia-noite UTC
        return !day;
    }

    /** Hora UTC (0–24) do nascer ou pôr do sol, ou null se o sol não nasce/não se põe nesse dia. */
    private static Double sunTimeUtc(int dayOfYear, double lat, double lon, boolean rising) {
        double zenith = 90.833; // inclui a refração e o tamanho do disco solar
        double lngHour = lon / 15.0;
        double t = dayOfYear + ((rising ? 6 : 18) - lngHour) / 24.0;
        double m = 0.9856 * t - 3.289;
        double l = normalize(m + 1.916 * sinD(m) + 0.020 * sinD(2 * m) + 282.634, 360);
        double ra = normalize(Math.toDegrees(Math.atan(0.91764 * tanD(l))), 360);
        double lQuadrant = Math.floor(l / 90) * 90;
        double raQuadrant = Math.floor(ra / 90) * 90;
        ra = (ra + (lQuadrant - raQuadrant)) / 15.0;
        double sinDec = 0.39782 * sinD(l);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (cosD(zenith) - sinDec * sinD(lat)) / (cosDec * cosD(lat));
        if (cosH > 1 || cosH < -1) return null;
        double h = rising ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH));
        h /= 15.0;
        double localMean = h + ra - 0.06571 * t - 6.622;
        return normalize(localMean - lngHour, 24);
    }

    private static double normalize(double v, double max) {
        v %= max;
        return v < 0 ? v + max : v;
    }

    private static double sinD(double d) {
        return Math.sin(Math.toRadians(d));
    }

    private static double cosD(double d) {
        return Math.cos(Math.toRadians(d));
    }

    private static double tanD(double d) {
        return Math.tan(Math.toRadians(d));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }
}
