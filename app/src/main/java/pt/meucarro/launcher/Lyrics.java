package pt.meucarro.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Letra da música, do LRCLIB (base de letras gratuita e aberta, sem conta nem chave).
 * Só aceita a letra se o artista, o nome da música e a DURAÇÃO baterem certo
 * (mais vale "sem letra" do que a letra de outra música).
 * Cada letra fica guardada no rádio: uma música já ouvida tem letra mesmo sem internet.
 */
public final class Lyrics {

    public static final class Line {
        public final long t;      // ms desde o início da música (só nas letras com tempos)
        public final String text;

        Line(long t, String text) {
            this.t = t;
            this.text = text;
        }
    }

    public static final class Result {
        /** Linhas da letra (vazio se não houver). */
        public final List<Line> lines;
        /** As linhas têm o tempo em que são cantadas. */
        public final boolean synced;
        public final boolean instrumental;
        /** Falhou por falta de internet (não é "não existe"). */
        public final boolean offline;
        /** De onde veio a letra (para os créditos). */
        public String source = "LRCLIB";

        Result(List<Line> lines, boolean synced, boolean instrumental, boolean offline) {
            this.lines = lines;
            this.synced = synced;
            this.instrumental = instrumental;
            this.offline = offline;
        }

        public boolean found() {
            return !lines.isEmpty();
        }
    }

    public interface Callback {
        void onLyrics(String key, Result result);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String API = "https://lrclib.net/api/";
    /** Uma música sem letra volta a ser procurada passados 2 dias (as novas demoram a aparecer). */
    private static final long RETRY_NONE_MS = 2L * 24 * 3600 * 1000;

    private Lyrics() {
    }

    public static String key(String artist, String title, long durationMs) {
        return ArtFetcher.key(artist, title) + " | " + Math.round(durationMs / 1000.0);
    }

    public static void fetch(Context context, String artist, String title, String album, long durationMs, Callback cb) {
        final String key = key(artist, title, durationMs);
        final File dir = new File(context.getFilesDir(), "letras");
        EXEC.execute(() -> {
            Result r;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, Integer.toHexString(key.hashCode()) + ".json");
                JSONObject found = null;
                boolean offline = false;
                boolean cachedNone = false;
                if (f.exists()) {
                    JSONObject c = new JSONObject(read(f));
                    if (c.optBoolean("none")) {
                        cachedNone = System.currentTimeMillis() - f.lastModified() < RETRY_NONE_MS;
                    } else {
                        found = c;
                    }
                }
                if (found == null && !cachedNone) {
                    try {
                        found = search(artist, title, album, durationMs);
                        JSONObject save = found != null ? found : new JSONObject().put("none", true);
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            out.write(save.toString().getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (java.io.IOException e) {
                        offline = true; // sem internet: tenta outra vez da próxima vez
                    }
                }
                r = toResult(found, offline);
            } catch (Exception e) {
                r = new Result(new ArrayList<>(), false, false, false);
            }
            final Result result = r;
            MAIN.post(() -> cb.onLyrics(key, result));
        });
    }

    // ---------- Procura ----------

    private static JSONObject search(String artist, String title, String album, long durationMs) throws Exception {
        String a = artist == null ? "" : artist;
        String t = ArtFetcher.cleanTitle(title);
        long sec = Math.round(durationMs / 1000.0);
        // 1) Pedido exato: o próprio LRCLIB só responde se a duração bater certo (±2 s)
        if (sec > 0 && !a.isEmpty()) {
            StringBuilder q = new StringBuilder(API + "get?artist_name=").append(enc(a))
                    .append("&track_name=").append(enc(title)).append("&duration=").append(sec);
            if (album != null) q.append("&album_name=").append(enc(album));
            JSONObject o = getObject(q.toString());
            if (o != null && accept(o, artist, title, sec)) return o;
        }
        // 2) Pesquisa mais larga, mas com as mesmas regras (artista + nome + duração ±3 s)
        String[][] tries = {
                {a, t},
                {a.isEmpty() ? "" : ArtFetcher.mainArtist(a), t},
        };
        JSONObject best = null;
        int bestScore = 0;
        for (String[] tr : tries) {
            String url = API + "search?track_name=" + enc(tr[1]) + (tr[0].isEmpty() ? "" : "&artist_name=" + enc(tr[0]));
            JSONArray arr = getArray(url);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!accept(o, artist, title, sec)) continue;
                // Prefere: nome exato, depois com tempos, depois a duração mais próxima
                int sc = ArtFetcher.score(artist, title, o.optString("artistName"), o.optString("trackName")) * 100
                        + (hasText(o, "syncedLyrics") ? 50 : 0)
                        + (sec > 0 ? 10 - (int) Math.min(10, Math.abs(o.optDouble("duration", 0) - sec) * 3) : 0);
                if (sc > bestScore) {
                    bestScore = sc;
                    best = o;
                }
            }
            if (best != null) break;
        }
        if (best == null) best = lyricsOvh(a, t);
        return best;
    }

    /**
     * Reserva: lyrics.ovh (só letras sem tempos, e sem duração para confirmar a versão;
     * procura pelo artista e nome exatos). Se estiver em baixo, simplesmente não há letra.
     */
    private static JSONObject lyricsOvh(String artist, String title) {
        if (artist.isEmpty() || title.isEmpty()) return null;
        try {
            String body = get("https://api.lyrics.ovh/v1/" + path(ArtFetcher.mainArtist(artist)) + "/" + path(title));
            if (body == null) return null;
            String text = new JSONObject(body).optString("lyrics", "").replace("\r", "").trim();
            // Algumas letras começam com "Paroles de la chanson X par Y"
            if (text.startsWith("Paroles de la chanson")) {
                int nl = text.indexOf('\n');
                text = nl > 0 ? text.substring(nl + 1).trim() : "";
            }
            if (text.isEmpty()) return null;
            return new JSONObject().put("plainLyrics", text).put("source", "lyrics.ovh");
        } catch (Exception e) {
            return null;
        }
    }

    private static String path(String s) throws Exception {
        return URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20");
    }

    private static boolean accept(JSONObject o, String artist, String title, long sec) {
        if (!o.optBoolean("instrumental") && !hasText(o, "syncedLyrics") && !hasText(o, "plainLyrics")) return false;
        int score = ArtFetcher.score(artist, title, o.optString("artistName"), o.optString("trackName"));
        if (score == 0) return false;
        double d = o.optDouble("duration", 0);
        if (sec > 0 && d > 0) return Math.abs(d - sec) <= 3;
        return score == 2; // sem duração para comparar: só com o nome exato
    }

    private static boolean hasText(JSONObject o, String k) {
        return !o.isNull(k) && !o.optString(k, "").trim().isEmpty();
    }

    // ---------- Converter ----------

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    private static Result toResult(JSONObject o, boolean offline) {
        Result r = toResult0(o, offline);
        if (o != null) r.source = o.optString("source", "LRCLIB");
        return r;
    }

    private static Result toResult0(JSONObject o, boolean offline) {
        List<Line> lines = new ArrayList<>();
        if (o == null) return new Result(lines, false, false, offline);
        if (o.optBoolean("instrumental") && !hasText(o, "syncedLyrics") && !hasText(o, "plainLyrics")) {
            return new Result(lines, false, true, false);
        }
        if (hasText(o, "syncedLyrics")) {
            lines = parseLrc(o.optString("syncedLyrics"));
            if (!lines.isEmpty()) return new Result(lines, true, false, false);
        }
        if (hasText(o, "plainLyrics")) {
            for (String l : o.optString("plainLyrics").split("\\r?\\n")) lines.add(new Line(-1, l.trim()));
            // tira linhas vazias do início e do fim
            while (!lines.isEmpty() && lines.get(0).text.isEmpty()) lines.remove(0);
            while (!lines.isEmpty() && lines.get(lines.size() - 1).text.isEmpty()) lines.remove(lines.size() - 1);
        }
        return new Result(lines, false, false, false);
    }

    /** "[01:02.50] texto" → linhas ordenadas pelo tempo (uma linha pode ter vários tempos). */
    static List<Line> parseLrc(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = TIME.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    ms += Long.parseLong(f) * (f.length() == 1 ? 100 : f.length() == 2 ? 10 : 1);
                }
                times.add(ms);
                end = m.end();
            }
            if (times.isEmpty()) continue; // [ar:...] e outras etiquetas
            String text = raw.substring(end).trim();
            for (long t : times) out.add(new Line(t, text));
        }
        Collections.sort(out, (x, y) -> Long.compare(x.t, y.t));
        return out;
    }

    // ---------- Internet ----------

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s.trim(), "UTF-8");
    }

    private static JSONObject getObject(String url) throws Exception {
        String body = get(url);
        return body == null ? null : new JSONObject(body);
    }

    private static JSONArray getArray(String url) throws Exception {
        String body = get(url);
        return body == null ? null : new JSONArray(body);
    }

    /** null se não existe (404); IOException se não há internet. */
    private static String get(String address) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(address).openConnection();
        try {
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);
            con.setRequestProperty("User-Agent", "LauncherDoCarro/4 (https://github.com/Dinispg10/CarLauncher)");
            int code = con.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 2 * 1024 * 1024) break;
                }
                return out.toString("UTF-8");
            }
        } finally {
            con.disconnect();
        }
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
