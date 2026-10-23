package pt.meucarro.launcher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Atualizações pela internet: vê a última "Release" do repositório no GitHub,
 * e se for mais recente descarrega o APK e instala-o por cima (pede confirmação).
 */
public final class Updater {

    private static final long AUTO_CHECK_EVERY_MS = 6 * 60 * 60 * 1000L; // no máximo de 6 em 6 horas
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy;

    private Updater() {
    }

    public static String currentVersion() {
        return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    }

    public static boolean configured() {
        return BuildConfig.UPDATE_REPO != null && BuildConfig.UPDATE_REPO.contains("/");
    }

    /** Verificação automática ao ligar o carro: silenciosa, só avisa se houver versão nova. */
    public static void autoCheck(Activity activity) {
        if (!configured()) return;
        SharedPreferences p = activity.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        long last = p.getLong("update_check", 0);
        if (System.currentTimeMillis() - last < AUTO_CHECK_EVERY_MS) return;
        p.edit().putLong("update_check", System.currentTimeMillis()).apply();
        check(activity, true);
    }

    /** silent = não mostra nada se já estiver atualizada ou sem internet. */
    public static void check(Activity activity, boolean silent) {
        if (!configured()) {
            if (!silent) {
                Sheet.message(activity, "Atualizações",
                        "Esta versão foi feita no Android Studio sem repositório do GitHub configurado.\n\n"
                                + "Instale uma versão criada pelo GitHub (ver LEIA-ME, secção Atualizações) "
                                + "ou ponha updateRepo=utilizador/repositório em gradle.properties.");
            }
            return;
        }
        if (busy) return;
        busy = true;
        if (!silent) Toast.makeText(activity, "A procurar atualizações…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Release r = null;
            String error = null;
            try {
                r = latest();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final Release release = r;
            final String err = error;
            MAIN.post(() -> {
                busy = false;
                if (activity.isFinishing()) return;
                if (release == null) {
                    if (!silent) Sheet.message(activity, "Atualizações",
                            "Não foi possível ver se há atualizações.\n\n"
                                    + (err != null ? err : "Verifique a internet (hotspot)."));
                    return;
                }
                if (release.versionCode <= BuildConfig.VERSION_CODE || release.apkUrl == null) {
                    if (!silent) Sheet.message(activity, "Atualizações",
                            "Já tem a versão mais recente.\n\nVersão instalada: " + currentVersion());
                    return;
                }
                Sheet.create(activity, "Há uma versão nova")
                        .message("Versão " + release.name + "\n\n"
                                + (release.notes.isEmpty() ? "" : release.notes + "\n\n")
                                + "Instalada: " + currentVersion())
                        .button("Depois", false, null)
                        .button("Atualizar", true, () -> download(activity, release))
                        .show();
            });
        }).start();
    }

    // ---------- GitHub ----------

    private static final class Release {
        String name;
        String notes;
        int versionCode;
        String apkUrl;
    }

    private static Release latest() throws Exception {
        String body = new String(get("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest"), "UTF-8");
        JSONObject json = new JSONObject(body);
        Release r = new Release();
        String tag = json.optString("tag_name", "");
        r.versionCode = Integer.parseInt(tag.replaceAll("[^0-9]", "").isEmpty() ? "0" : tag.replaceAll("[^0-9]", ""));
        r.name = json.optString("name", tag);
        r.notes = json.optString("body", "").trim();
        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) {
                    r.apkUrl = a.optString("browser_download_url");
                    break;
                }
            }
        }
        return r;
    }

    private static byte[] get(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "LauncherDoCarro");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = conn.getResponseCode();
            if (code == 404) throw new Exception("Ainda não há nenhuma versão publicada no GitHub.");
            if (code != 200) throw new Exception("O GitHub respondeu com o código " + code + ".");
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    // ---------- Descarregar e instalar ----------

    private static void download(Activity activity, Release release) {
        Toast.makeText(activity, "A descarregar a atualização…", Toast.LENGTH_LONG).show();
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            File file = new File(app.getCacheDir(), "update.apk");
            String error = null;
            HttpURLConnection conn = null;
            try {
                // O GitHub redireciona o download; HttpURLConnection segue sozinho (https → https)
                conn = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "LauncherDoCarro");
                conn.setInstanceFollowRedirects(true);
                if (conn.getResponseCode() != 200) throw new Exception("código " + conn.getResponseCode());
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String err = error;
            MAIN.post(() -> {
                if (err != null) {
                    Toast.makeText(app, "Falhou o download: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                install(app, file);
            });
        }).start();
    }

    private static void install(Context context, File apk) {
        try {
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("CarLauncher.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= 0x02000000; // FLAG_MUTABLE
                PendingIntent pi = PendingIntent.getBroadcast(context, id,
                        new Intent(context, UpdateReceiver.class), flags);
                session.commit(pi.getIntentSender());
            }
        } catch (Exception e) {
            Toast.makeText(context, "Não foi possível instalar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
