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
                    Thread.sleep(30 * 60 * 1000L);
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
            if (method.equals("POST") && path.startsWith("/music?")) {
                text(out, 200, "text/plain; charset=utf-8", receiveMusic(in, length, param(path, "name")));
                return;
            }
            if (method.equals("POST") && path.startsWith("/music-delete?")) {
                int n = OfflineMusic.deleteAlbum(app, param(path, "key"));
                notifyMusic();
                text(out, 200, "text/plain; charset=utf-8", "✓ " + n + " músicas apagadas");
                return;
            }
            if (path.startsWith("/music-list")) {
                text(out, 200, "application/json; charset=utf-8", musicList());
                return;
            }
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
            } else if (path.startsWith("/musica")) {
                StringBuilder m = new StringBuilder("== Sessões de música agora ==\n");
                for (String l : MediaHub.get(app).describeSessions()) m.append(l).append('\n');
                m.append("\n== Registo (mais recente primeiro) ==\n").append(AudioLog.dump());
                text(out, 200, "text/plain; charset=utf-8", m.toString());
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

    // ---------- Músicas offline ----------

    private static String param(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String kv : path.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** Recebe um ficheiro de música (o corpo do pedido é o próprio ficheiro) e guarda-o na pasta offline. */
    private static String receiveMusic(InputStream in, long length, String name) {
        try {
            if (!OfflineMusic.isAudio(name)) return "✗ " + name + ": não é um ficheiro de música";
            if (length <= 0 || length > 400L * 1024 * 1024) return "✗ " + name + ": tamanho inválido";
            File dir = OfflineMusic.folder(app);
            if (dir.getUsableSpace() < length + 200L * 1024 * 1024) return "✗ Sem espaço no rádio";
            String clean = name.replaceAll("[\\/:*?\"<>|]", "_").trim();
            if (clean.length() > 150) clean = clean.substring(clean.length() - 150);
            File f = new File(dir, clean);
            if (f.exists() && f.length() == length) {
                drain(in, length);
                return "✓ " + name + " (já estava no rádio)";
            }
            File tmp = new File(dir, "." + clean + ".part");
            long got = 0;
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                while (got < length) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, length - got));
                    if (n < 0) break;
                    fo.write(buf, 0, n);
                    got += n;
                }
            }
            if (got < length) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return "✗ " + name + ": a ligação caiu a meio";
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
            notifyMusic();
            return "✓ " + name;
        } catch (Exception e) {
            return "✗ " + name + ": " + e.getMessage();
        }
    }

    private static void drain(InputStream in, long length) throws java.io.IOException {
        byte[] buf = new byte[64 * 1024];
        long got = 0;
        while (got < length) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, length - got));
            if (n < 0) break;
            got += n;
        }
    }

    private static void notifyMusic() {
        OfflineMusic.invalidate();
        java.util.function.Consumer<String> cb = onReceived;
        if (cb != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.accept("music"));
    }

    private static String musicList() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (OfflineMusic.Album a : OfflineMusic.albums(app)) {
                arr.put(new org.json.JSONObject().put("key", a.key).put("name", a.name)
                        .put("artist", a.artist == null ? "" : a.artist).put("n", a.tracks.size()));
            }
            o.put("albums", arr);
            o.put("used", OfflineMusic.usedBytes(app));
            o.put("free", OfflineMusic.folder(app).getUsableSpace());
        } catch (Exception ignored) {
        }
        return o.toString();
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

    /** Envio de várias músicas de uma vez, uma a uma, com o progresso de cada. */
    private static final String MUSIC_SECTION =
            "<h3 id=musica>Músicas offline</h3>"
            + "<p style='color:#9aa0a6'>MP3 (ou M4A, FLAC…). Podes escolher várias de uma vez. "
            + "Ficam guardadas no rádio para ouvir sem telemóvel; os álbuns e capas vêm dos próprios ficheiros.</p>"
            + "<input type=file id=mf multiple accept='audio/*,.mp3,.m4a,.aac,.flac,.ogg,.opus,.wav' style='font-size:16px'> "
            + "<button id=mb onclick='up()' style='font-size:16px;padding:8px 18px;border-radius:10px;border:0;background:#5eead4'>Enviar para o rádio</button>"
            + "<div id=mp style='margin:12px 0;font-size:16px;line-height:1.6'></div>"
            + "<div id=ml style='margin:6px 0 26px;font-size:16px'></div>"
            + "<script>"
            + "function list(){fetch('/music-list').then(r=>r.json()).then(d=>{var h='';"
            + "(d.albums||[]).forEach(a=>{h+='<div style=\"display:flex;align-items:center;padding:8px 0;border-bottom:1px solid #22262d\"><div style=\"flex:1\"><b>'+esc(a.name)+'</b><br><span style=\"color:#9aa0a6\">'+esc(a.artist)+' · '+a.n+' músicas</span></div>'"
            + "+'<button data-k=\"'+encodeURIComponent(a.key)+'\" onclick=\"del(this.dataset.k)\" style=\"font-size:15px;padding:6px 12px;border-radius:8px;border:1px solid #444;background:none;color:#f87171\">Apagar</button></div>';});"
            + "h+='<p style=\"color:#9aa0a6\">Ocupado: '+(d.used/1048576).toFixed(0)+' MB · livre no rádio: '+(d.free/1073741824).toFixed(1)+' GB</p>';"
            + "document.getElementById('ml').innerHTML=h;});}"
            + "function esc(s){return String(s).replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));}"
            + "function del(k){if(confirm('Apagar este álbum do rádio?'))fetch('/music-delete?key='+k,{method:'POST'}).then(list);}"
            + "function up(){var fs=[].slice.call(document.getElementById('mf').files);if(!fs.length)return;"
            + "var box=document.getElementById('mp');box.innerHTML='';document.getElementById('mb').disabled=true;var i=0;"
            + "(function next(){if(i>=fs.length){document.getElementById('mb').disabled=false;box.innerHTML+='<b>Pronto! Já estão no rádio.</b>';list();return;}"
            + "var f=fs[i++],line=document.createElement('div');line.textContent=f.name+' …';box.appendChild(line);"
            + "var x=new XMLHttpRequest();x.open('POST','/music?name='+encodeURIComponent(f.name));"
            + "x.upload.onprogress=function(e){if(e.lengthComputable)line.textContent=f.name+' … '+Math.round(e.loaded/e.total*100)+'%';};"
            + "x.onload=function(){line.textContent=x.responseText;next();};x.onerror=function(){line.textContent='✗ '+f.name+': a ligação falhou';next();};"
            + "x.send(f);})();}"
            + "list();</script>";

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
        sb.append(MUSIC_SECTION);
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
        sb.append("<li style='margin:10px 0'><a ").append(a).append(" href='/musica.txt' download>musica.txt</a>")
                .append(" (o que o telemóvel envia: nomes das músicas)</li>");
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
