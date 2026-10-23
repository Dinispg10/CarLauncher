package pt.meucarro.launcher;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Diagnóstico do carro: descobre que app/sistema do rádio lê os dados do carro (CAN)
 * e que "portas" ela abre a outras apps. Só lê informação; não mexe em nada.
 */
public final class CarDiagnostics {

    // Propriedades de sistema que costumam identificar a plataforma destes rádios
    private static final String[] PROPS = {
            "ro.board.platform", "ro.hardware", "ro.product.board", "ro.product.model",
            "ro.build.display.id", "ro.build.version.incremental",
            "ro.fyt.platform", "ro.build.fytmanufacturer", "sys.fyt.platform", "ro.fota.platform",
            "ro.product.customize", "persist.sys.platform", "ro.mtc.version", "ro.product.mcu",
            "persist.sys.canbus", "persist.sys.cantype", "persist.sys.canbustype", "ro.car.canbus",
    };

    private static final String[] KEYWORDS = {
            "canbus", "canbox", "cansetting", ".can.", ".can", "can.", "carset", "carinfo", "car.info",
            "syu", "microntek", "mtc", "carsetting", "originalcar", "vehicle", "decoder",
    };

    private CarDiagnostics() {
    }

    public static String report(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("RÁDIO\n");
        out.append("Fabricante: ").append(Build.MANUFACTURER).append(" · ").append(Build.BRAND).append('\n');
        out.append("Modelo: ").append(Build.MODEL).append(" · ").append(Build.DEVICE).append('\n');
        out.append("Hardware: ").append(Build.HARDWARE).append(" · ").append(Build.BOARD).append('\n');
        out.append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        for (String key : PROPS) {
            String value = prop(key);
            if (value != null && !value.isEmpty()) out.append(key).append(" = ").append(value).append('\n');
        }

        out.append("\nAPPS DO CARRO\n");
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Exception e) {
            out.append("Não consegui listar as apps: ").append(e.getMessage());
            return out.toString();
        }
        int found = 0;
        for (ApplicationInfo app : apps) {
            String pkg = app.packageName;
            String label = String.valueOf(pm.getApplicationLabel(app));
            if (!looksLikeCarApp(pkg, label)) continue;
            found++;
            out.append("\n• ").append(label).append("\n   ").append(pkg).append('\n');
            describe(pm, pkg, out);
            if (found >= 12) {
                out.append("\n(há mais, mostrei as primeiras 12)\n");
                break;
            }
        }
        if (found == 0) out.append("Nenhuma encontrada pelo nome.\n");

        // Procura mais funda: propriedades e definições do sistema com nomes ligados ao carro
        out.append("\nPROPRIEDADES DO SISTEMA\n");
        int props = 0;
        try {
            Process proc = Runtime.getRuntime().exec("getprop");
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = r.readLine()) != null && props < 40) {
                String l = line.toLowerCase(Locale.ROOT);
                if (matchesCarWord(l)) {
                    out.append(line.replace("]: [", " = ").replace("[", "").replace("]", "")).append('\n');
                    props++;
                }
            }
            r.close();
        } catch (Exception e) {
            out.append("(não foi possível ler)\n");
        }
        if (props == 0) out.append("Nenhuma.\n");

        out.append("\nDEFINIÇÕES DO SISTEMA\n");
        int settings = 0;
        for (String table : new String[]{"system", "global", "secure"}) {
            try (android.database.Cursor c = context.getContentResolver().query(
                    android.net.Uri.parse("content://settings/" + table), new String[]{"name", "value"},
                    null, null, null)) {
                while (c != null && c.moveToNext() && settings < 40) {
                    String name = c.getString(0);
                    if (name != null && matchesCarWord(name.toLowerCase(Locale.ROOT))) {
                        out.append(table).append(": ").append(name).append(" = ").append(c.getString(1)).append('\n');
                        settings++;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (settings == 0) out.append("Nenhuma.\n");
        return out.toString().trim();
    }

    private static final String[] CAR_WORDS = {
            "can", "xygala", "xy.", "car", "temp", "fuel", "oil", "door", "mileage", "odo", "rpm", "mcu",
    };

    private static boolean matchesCarWord(String s) {
        for (String w : CAR_WORDS) if (s.contains(w)) return true;
        return false;
    }

    /** Apps do carro que vale a pena analisar. */
    public static final String[] CAR_PACKAGES = {"com.xygala.canbus", "com.xygala.pvcanset"};

    /**
     * Copia os ficheiros de instalação (APK) das apps do carro para a pasta Download,
     * para poderem ser analisados. Não altera nada no rádio. Devolve o resumo.
     */
    @SuppressWarnings("deprecation")
    public static String exportApks(Context context) {
        StringBuilder out = new StringBuilder();
        java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        PackageManager pm = context.getPackageManager();
        for (String pkg : CAR_PACKAGES) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                java.io.File src = new java.io.File(info.sourceDir);
                java.io.File dst = new java.io.File(dir, pkg + ".apk");
                try (java.io.InputStream in = new java.io.FileInputStream(src);
                     java.io.OutputStream o = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                out.append("✓ ").append(dst.getName()).append(" (")
                        .append(dst.length() / (1024 * 1024)).append(" MB)\n");
            } catch (PackageManager.NameNotFoundException e) {
                out.append("– ").append(pkg).append(": não instalada\n");
            } catch (Exception e) {
                out.append("✗ ").append(pkg).append(": ").append(e.getMessage()).append('\n');
            }
        }
        return out.toString().trim();
    }

    private static boolean looksLikeCarApp(String pkg, String label) {
        if (pkg.equals("pt.meucarro.launcher")) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        String l = label.toLowerCase(Locale.ROOT).replace(" ", "");
        for (String k : KEYWORDS) {
            if (p.contains(k)) return true;
        }
        return l.contains("can") && (l.contains("set") || l.contains("bus") || l.contains("info"))
                || l.contains("carsetting") || l.contains("carinfo") || l.contains("originalcar");
    }

    @SuppressWarnings("deprecation")
    private static void describe(PackageManager pm, String pkg, StringBuilder out) {
        int flags = PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS
                | PackageManager.GET_RECEIVERS | PackageManager.GET_ACTIVITIES;
        PackageInfo info;
        try {
            info = pm.getPackageInfo(pkg, flags);
        } catch (Exception e) {
            out.append("   (sem detalhes)\n");
            return;
        }
        if (info.providers != null) {
            for (ProviderInfo p : info.providers) {
                out.append("   provider: ").append(p.authority).append(p.exported ? " (aberto)" : "").append('\n');
            }
        }
        if (info.services != null) {
            int n = 0;
            for (ServiceInfo s : info.services) {
                if (!s.exported) continue;
                out.append("   service: ").append(shortName(pkg, s.name)).append('\n');
                if (++n >= 8) break;
            }
        }
        if (info.receivers != null) {
            int n = 0;
            for (ActivityInfo r : info.receivers) {
                if (!r.exported) continue;
                out.append("   receiver: ").append(shortName(pkg, r.name)).append('\n');
                if (++n >= 6) break;
            }
        }
        if (info.activities != null) {
            int n = 0;
            for (ActivityInfo a : info.activities) {
                if (!a.exported) continue;
                out.append("   activity: ").append(shortName(pkg, a.name)).append('\n');
                if (++n >= 6) break;
            }
        }
    }

    private static String shortName(String pkg, String name) {
        return name.startsWith(pkg) ? name.substring(pkg.length()) : name;
    }

    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Exception e) {
            return null;
        }
    }
}
