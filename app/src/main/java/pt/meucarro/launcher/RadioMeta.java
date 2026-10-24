package pt.meucarro.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descobre a música que está a tocar na rádio online ("artista – música") e procura a capa.
 * Rádios da StreamTheWorld (RFM, Mega Hits, Renascença): pela lista "now playing" da Triton.
 * As outras: pelos dados que vêm dentro da própria emissão (ICY "StreamTitle").
 * Pergunta de 20 em 20 segundos só enquanto a rádio está a tocar.
 */
public final class RadioMeta {

    private static final long POLL_MS = 20000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static volatile String artist;
    public static volatile String title;
    public static volatile Bitmap art;
    private static volatile int forIndex = -1;
    private static volatile int generation;
    private static Runnable onChange;

    private RadioMeta() {
    }

    public static boolean hasSong(int index) {
        return index == forIndex && title != null;
    }

    /** "Artista – Música" (ou só a música). */
    public static String songLine() {
        if (title == null) return null;
        return artist == null || artist.isEmpty() ? title : artist + " – " + title;
    }

    /** Começa a seguir a rádio indicada (pára a anterior). onChange corre no fio principal. */
    public static synchronized void start(Context context, int index, Runnable changed) {
        onChange = changed;
        if (index == forIndex && generation > 0 && running) return;
        final int my = ++generation;
        forIndex = index;
        artist = null;
        title = null;
        art = null;
        running = true;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            while (my == generation) {
                try {
                    poll(app, index, my);
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException ignored) {
                }
            }
        }, "RadioMeta");
        t.setDaemon(true);
        t.start();
    }

    private static volatile boolean running;

    public static synchronized void stop() {
        generation++;
        running = false;
        boolean had = title != null;
        artist = null;
        title = null;
        art = null;
        forIndex = -1;
        if (had) fire();
    }

    private static void fire() {
        Runnable r = onChange;
        if (r != null) MAIN.post(r);
    }

    private static void poll(Context app, int index, int my) {
        RadioStations.Station station = RadioStations.ALL[index];
        String[] song = null;
        String mount = tritonMount(station);
        if (mount != null) song = triton(mount);
        if (song == null) {
            String url = icyUrl(station);
            if (url != null) song = icy(url);
        }
        if (my != generation) return;
        if (song != null && !useful(song, station)) song = null;
        String newArtist = song == null ? null : song[0];
        String newTitle = song == null ? null : song[1];
        if (eq(newArtist, artist) && eq(newTitle, title)) return;
        artist = newArtist;
        title = newTitle;
        art = null;
        AudioLog.add("Rádio: a tocar " + (newTitle == null ? "(sem nome da música)" : songLine()));
        fire();
        if (newTitle != null && newArtist != null) {
            final String key = ArtFetcher.key(newArtist, newTitle);
            MAIN.post(() -> ArtFetcher.fetch(app, newArtist, newTitle, (k, bitmap) -> {
                if (my == generation && key.equals(ArtFetcher.key(artist, title)) && bitmap != null) {
                    art = bitmap;
                    fire();
                }
            }));
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Ignora textos que não são músicas (nome da rádio, publicidade…). */
    private static boolean useful(String[] song, RadioStations.Station station) {
        String t = song[1] == null ? "" : song[1].trim();
        if (t.isEmpty()) return false;
        String all = ((song[0] == null ? "" : song[0]) + " " + t).toLowerCase(Locale.ROOT);
        String name = station.name.toLowerCase(Locale.ROOT);
        for (String bad : new String[]{"publicidade", "pub ", "spot", "jingle", "advert", "commercial break",
                "unknown", "desconhecido"}) {
            if (all.contains(bad)) return false;
        }
        // Sem artista e só com o nome da rádio (ex.: "Rádio Comercial") não é uma música
        return !(song[0] == null && (all.contains(name) || all.contains(station.id)));
    }

    // ---------- StreamTheWorld / Triton ----------

    private static String tritonMount(RadioStations.Station station) {
        for (String u : station.urls) {
            if (!u.contains("streamtheworld.com")) continue;
            String last = u.substring(u.lastIndexOf('/') + 1);
            int dot = last.indexOf('.');
            return dot > 0 ? last.substring(0, dot) : last;
        }
        return null;
    }

    private static String[] triton(String mount) {
        String xml = get("https://np.tritondigital.com/public/nowplaying?mountName=" + mount
                + "&numberToFetch=1&eventType=track&rnd=" + System.currentTimeMillis());
        if (xml == null || !xml.contains("cue_title")) return null;
        String t = prop(xml, "cue_title");
        String a = prop(xml, "track_artist_name");
        // A última música pode ser antiga (agora há notícias ou publicidade)
        try {
            long start = Long.parseLong(prop(xml, "cue_time_start"));
            long dur = Long.parseLong(prop(xml, "cue_time_duration"));
            if (start > 0 && dur > 0 && System.currentTimeMillis() > start + dur + 90000) return null;
        } catch (Exception ignored) {
        }
        if (t == null) return null;
        return new String[]{a, t};
    }

    private static String prop(String xml, String name) {
        Matcher m = Pattern.compile("name=\"" + name + "\"[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</property>",
                Pattern.DOTALL).matcher(xml);
        if (!m.find()) return null;
        String v = m.group(1).trim();
        return v.isEmpty() ? null : v.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"");
    }

    private static String get(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LauncherDoCarro");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 200000) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- ICY (dentro da emissão) ----------

    private static String icyUrl(RadioStations.Station station) {
        for (String u : station.urls) if (!u.contains(".m3u8")) return u;
        return null;
    }

    private static String[] icy(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Icy-MetaData", "1");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LauncherDoCarro");
            String mi = c.getHeaderField("icy-metaint");
            if (mi == null) return null;
            int metaint = Integer.parseInt(mi.trim());
            if (metaint <= 0 || metaint > 200000) return null;
            try (InputStream in = c.getInputStream()) {
                byte[] skip = new byte[8192];
                int left = metaint;
                while (left > 0) {
                    int n = in.read(skip, 0, Math.min(skip.length, left));
                    if (n < 0) return null;
                    left -= n;
                }
                int len = in.read() * 16;
                if (len <= 0) return null;
                byte[] meta = new byte[len];
                int got = 0;
                while (got < len) {
                    int n = in.read(meta, got, len - got);
                    if (n < 0) break;
                    got += n;
                }
                String s = new String(meta, 0, got, StandardCharsets.UTF_8);
                if (s.indexOf('�') >= 0) s = new String(meta, 0, got, StandardCharsets.ISO_8859_1);
                Matcher m = Pattern.compile("StreamTitle='(.*?)';", Pattern.DOTALL).matcher(s);
                if (!m.find()) return null;
                return split(m.group(1).trim());
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** "Artista - Música" → {artista, música}. */
    static String[] split(String s) {
        if (s == null || s.isEmpty()) return null;
        int at = s.indexOf(" - ");
        if (at > 0) return new String[]{s.substring(0, at).trim(), s.substring(at + 3).trim()};
        return new String[]{null, s};
    }
}
