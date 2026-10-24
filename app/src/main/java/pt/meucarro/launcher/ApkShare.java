package pt.meucarro.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Pequeno servidor de páginas, só na rede local, para passar as apps do carro (CanSetting)
 * para o PC sem pen: o PC liga-se à mesma rede (hotspot do telemóvel) e abre o endereço no browser.
 * Só lê ficheiros; não altera nada no rádio nem no carro. Desliga-se sozinho ao fim de 15 minutos.
 */
public final class ApkShare {

    /** Apps do carro (XYGala) que vale a pena analisar. */
    static final String[] CAR_PACKAGES = {"com.xygala.canbus", "com.xygala.pvcanset"};

    public static final int PORT = 8080;
    private static ServerSocket server;
    private static Context app;

    private ApkShare() {
    }

    public static synchronized boolean isOn() {
        return server != null && !server.isClosed();
    }

    /** Liga o servidor e devolve o endereço a abrir no PC (ou null se não houver rede). */
    public static synchronized String start(Context context) {
        app = context.getApplicationContext();
        String ip = localIp();
        if (ip == null) return null;
        if (!isOn()) {
            try {
                server = new ServerSocket(PORT);
            } catch (Exception e) {
                return null;
            }
            ServerSocket s = server;
            Thread t = new Thread(() -> loop(s), "ApkShare");
            t.setDaemon(true);
            t.start();
            Thread stop = new Thread(() -> {
                try {
                    Thread.sleep(15 * 60 * 1000L);
                } catch (InterruptedException ignored) {
                }
                stop();
            });
            stop.setDaemon(true);
            stop.start();
        }
        return "http://" + ip + ":" + PORT;
    }

    public static synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
    }

    private static void loop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket c = s.accept();
                Thread t = new Thread(() -> handle(c));
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                return;
            }
        }
    }

    private static void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // ignora os cabeçalhos
            }
            OutputStream out = sock.getOutputStream();
            if (path.endsWith(".apk")) {
                String pkg = path.substring(1, path.length() - 4);
                File f = apkFile(pkg);
                if (f == null) {
                    text(out, 404, "text/plain", "Não encontrado");
                    return;
                }
                String head = "HTTP/1.0 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n"
                        + "Content-Length: " + f.length() + "\r\nContent-Disposition: attachment; filename=\""
                        + pkg + ".apk\"\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));
                try (InputStream fin = new FileInputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = fin.read(buf)) > 0) out.write(buf, 0, n);
                }
                out.flush();
            } else if (path.startsWith("/obd")) {
                text(out, 200, "text/plain; charset=utf-8", Obd.infoText() + "\n" + Obd.logText());
            } else if (path.startsWith("/info")) {
                text(out, 200, "text/plain; charset=utf-8", info());
            } else {
                text(out, 200, "text/html; charset=utf-8", page());
            }
        } catch (Exception ignored) {
        }
    }

    private static void text(OutputStream out, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.0 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static File apkFile(String pkg) {
        boolean known = false;
        for (String p : CAR_PACKAGES) if (p.equals(pkg)) known = true;
        if (!known) return null;
        try {
            ApplicationInfo info = app.getPackageManager().getApplicationInfo(pkg, 0);
            File f = new File(info.sourceDir);
            return f.canRead() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String page() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>")
                .append("<title>Apps do carro</title><body style='font-family:sans-serif;background:#0b0d10;")
                .append("color:#f2f3f5;padding:24px;font-size:18px'><h2>Apps do carro (Launcher do Carro)</h2>")
                .append("<p>Descarregue os ficheiros e anexe-os na conversa com o Claude.</p><ul>");
        for (String pkg : CAR_PACKAGES) {
            File f = apkFile(pkg);
            if (f != null) {
                sb.append("<li style='margin:12px 0'><a style='color:#5eead4' href='/").append(pkg).append(".apk'>")
                        .append(pkg).append(".apk</a> (").append(f.length() / 1024).append(" KB)</li>");
            } else {
                sb.append("<li>").append(pkg).append(": não disponível</li>");
            }
        }
        sb.append("<li style='margin:12px 0'><a style='color:#5eead4' href='/obd.txt' download>obd.txt</a>")
                .append(" (registo do adaptador do motor)</li>");
        sb.append("<li style='margin:12px 0'><a style='color:#5eead4' href='/info.txt' download>info.txt</a>")
                .append(" (resumo das apps)</li></ul></body>");
        return sb.toString();
    }

    /** Resumo do que as apps do carro declaram (serviços, recetores, fornecedores de dados). */
    private static String info() {
        PackageManager pm = app.getPackageManager();
        StringBuilder sb = new StringBuilder();
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS | PackageManager.GET_META_DATA;
        for (String pkg : CAR_PACKAGES) {
            sb.append("=== ").append(pkg).append(" ===\n");
            try {
                PackageInfo p = pm.getPackageInfo(pkg, flags);
                sb.append("versão: ").append(p.versionName).append(" (").append(p.versionCode).append(")\n");
                sb.append("ficheiro: ").append(p.applicationInfo.sourceDir).append('\n');
                sb.append("uid partilhado: ").append(p.sharedUserId).append('\n');
                list(sb, "atividades", p.activities);
                list(sb, "serviços", p.services);
                list(sb, "recetores", p.receivers);
                if (p.providers != null) {
                    for (ProviderInfo pi : p.providers) {
                        sb.append("  fornecedor ").append(pi.name).append(" authority=").append(pi.authority)
                                .append(" exported=").append(pi.exported).append(" read=").append(pi.readPermission)
                                .append('\n');
                    }
                }
                if (p.requestedPermissions != null) {
                    sb.append("permissões pedidas:\n");
                    for (String perm : p.requestedPermissions) sb.append("  ").append(perm).append('\n');
                }
                if (p.permissions != null) {
                    sb.append("permissões próprias:\n");
                    for (PermissionInfo perm : p.permissions) sb.append("  ").append(perm.name).append('\n');
                }
            } catch (Exception e) {
                sb.append("não instalada\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void list(StringBuilder sb, String title, ComponentInfo[] items) {
        if (items == null) return;
        sb.append(title).append(":\n");
        for (ComponentInfo ci : items) {
            sb.append("  ").append(ci.name).append(" exported=").append(ci.exported);
            if (ci instanceof android.content.pm.ServiceInfo && ((android.content.pm.ServiceInfo) ci).permission != null) {
                sb.append(" perm=").append(((android.content.pm.ServiceInfo) ci).permission);
            }
            sb.append('\n');
        }
    }

    private static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ni.getName().startsWith("wlan")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}
