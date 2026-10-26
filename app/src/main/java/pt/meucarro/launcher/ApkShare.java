package pt.meucarro.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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

    /** Chamado (no fio principal) quando o PC envia um ficheiro: "sound" ou "photo". */
    public static volatile java.util.function.Consumer<String> onReceived;
    public static final String PHOTO_FILE = "car_photo";

    private static void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(30000);
            InputStream in = new java.io.BufferedInputStream(sock.getInputStream());
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] parts = lines[0].split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            long length = 0;
            String type = "";
            for (String l : lines) {
                String low = l.toLowerCase(java.util.Locale.ROOT);
                if (low.startsWith("content-length:")) length = Long.parseLong(l.substring(15).trim());
                if (low.startsWith("content-type:")) type = l.substring(13).trim();
            }
            OutputStream out = sock.getOutputStream();
            if (method.equals("POST") && path.startsWith("/upload")) {
                String what = path.contains("photo") ? "photo" : "sound";
                String msg = receive(in, length, type, what);
                text(out, 200, "text/html; charset=utf-8", page(msg));
                return;
            }
            if (path.endsWith(".apk")) {
                String pkg = path.substring(1, path.length() - 4);
                File f = apkFile(pkg);
                if (f == null) {
                    text(out, 404, "text/plain", "Não encontrado");
                    return;
                }
                String h = "HTTP/1.0 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n"
                        + "Content-Length: " + f.length() + "\r\nContent-Disposition: attachment; filename=\""
                        + pkg + ".apk\"\r\nConnection: close\r\n\r\n";
                out.write(h.getBytes(StandardCharsets.UTF_8));
                try (InputStream fin = new FileInputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = fin.read(buf)) > 0) out.write(buf, 0, n);
                }
                out.flush();
            } else if (path.startsWith("/can")) {
                text(out, 200, "text/plain; charset=utf-8", CanBox.dump());
            } else if (path.startsWith("/obd")) {
                text(out, 200, "text/plain; charset=utf-8", Obd.infoText() + "\n" + Obd.logText());
            } else if (path.startsWith("/info")) {
                text(out, 200, "text/plain; charset=utf-8", info());
            } else {
                text(out, 200, "text/html; charset=utf-8", page(null));
            }
        } catch (Exception ignored) {
        }
    }

    /** Lê os cabeçalhos do pedido (até à linha em branco). */
    private static String readHead(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int last4 = 0;
        int ch;
        while ((ch = in.read()) >= 0) {
            b.write(ch);
            last4 = (last4 << 8) | ch;
            if (last4 == 0x0D0A0D0A) break;
            if (b.size() > 16384) return null;
        }
        return b.size() == 0 ? null : b.toString("ISO-8859-1");
    }

    /** Recebe um ficheiro de um formulário (multipart) e guarda-o como som de arranque ou foto do carro. */
    private static String receive(InputStream in, long length, String type, String what) {
        try {
            if (length <= 0 || length > 20L * 1024 * 1024) return "✗ Ficheiro demasiado grande (máximo 20 MB).";
            int at = type.indexOf("boundary=");
            if (at < 0) return "✗ Pedido inválido.";
            String boundary = "--" + type.substring(at + 9).replace("\"", "").trim();
            byte[] body = new byte[(int) length];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            int start = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
            if (start < 0) return "✗ Pedido inválido.";
            start += 4;
            int end = indexOf(body, ("\r\n" + boundary).getBytes(StandardCharsets.ISO_8859_1), start);
            if (end < 0) end = got;
            if (end - start < 100) return "✗ Escolha um ficheiro primeiro.";
            String name = what.equals("photo") ? PHOTO_FILE : StartSound.CUSTOM_FILE;
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(app.getFilesDir(), name))) {
                fo.write(body, start, end - start);
            }
            if (what.equals("sound")) StartSound.setChoice(app, "custom");
            java.util.function.Consumer<String> cb = onReceived;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (what.equals("sound")) StartSound.play(app, "custom");
                if (cb != null) cb.accept(what);
            });
            return what.equals("photo") ? "✓ Foto recebida: já está no ecrã inicial do rádio."
                    : "✓ Som recebido: já é o som de arranque (deve ter tocado agora no rádio).";
        } catch (Exception e) {
            return "✗ Não consegui receber: " + e.getMessage();
        }
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int k = 0; k < pattern.length; k++) if (data[i + k] != pattern[k]) continue outer;
            return i;
        }
        return -1;
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

    private static String page(String message) {
        String a = "style='color:#5eead4'";
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>")
                .append("<title>Launcher do Carro</title><body style='font-family:sans-serif;background:#0b0d10;")
                .append("color:#f2f3f5;padding:24px;font-size:18px;max-width:760px'><h2>Launcher do Carro</h2>");
        if (message != null) {
            sb.append("<p style='padding:14px 18px;border-radius:14px;background:#13161b;border:1px solid #2c313a'>")
                    .append(message).append("</p>");
        }
        String form = "<form method=post enctype=multipart/form-data style='margin:10px 0 26px' action='/upload?t=%s'>"
                + "<input type=file name=f accept='%s' style='font-size:16px'> "
                + "<button style='font-size:16px;padding:8px 18px;border-radius:10px;border:0;background:#5eead4'>"
                + "Enviar para o rádio</button></form>";
        sb.append("<h3>Som de arranque</h3><p style='color:#9aa0a6'>MP3 ou outro áudio curto (toca no máximo 8 s).</p>")
                .append(String.format(form, "sound", "audio/*"));
        sb.append("<h3>Foto do carro</h3><p style='color:#9aa0a6'>JPG ou PNG, de preferência deitada (horizontal).</p>")
                .append(String.format(form, "photo", "image/*"));
        sb.append("<h3>Para o Claude analisar</h3><ul>");
        for (String pkg : CAR_PACKAGES) {
            File f = apkFile(pkg);
            if (f != null) {
                sb.append("<li style='margin:10px 0'><a ").append(a).append(" href='/").append(pkg).append(".apk'>")
                        .append(pkg).append(".apk</a> (").append(f.length() / 1024).append(" KB)</li>");
            }
        }
        sb.append("<li style='margin:10px 0'><a ").append(a).append(" href='/can.txt' download>can.txt</a>")
                .append(" (mensagens da caixa CAN: ").append(CanBox.total()).append(")</li>");
        sb.append("<li style='margin:10px 0'><a ").append(a).append(" href='/obd.txt' download>obd.txt</a>")
                .append(" (registo do adaptador do motor)</li>");
        sb.append("<li style='margin:10px 0'><a ").append(a).append(" href='/info.txt' download>info.txt</a>")
                .append(" (resumo das apps do carro)</li></ul></body>");
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
