package pt.meucarro.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vai buscar a capa do álbum à internet (pesquisa pública do iTunes, sem
 * conta nem chave) a partir do artista e do nome da música.
 * Guarda as capas no rádio, por isso cada uma só é descarregada uma vez.
 */
public final class ArtFetcher {

    public interface Callback {
        /** bitmap é null se não houver capa (sem internet ou não encontrada). */
        void onArt(String key, Bitmap bitmap);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> NOT_FOUND = new HashSet<>();
    private static final Map<String, Bitmap> MEMORY = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 12;
        }
    };

    private ArtFetcher() {
    }

    public static String key(String artist, String title) {
        return ((artist == null ? "" : artist) + " | " + (title == null ? "" : title)).toLowerCase(Locale.ROOT).trim();
    }

    public static void fetch(Context context, String artist, String title, Callback callback) {
        final String key = key(artist, title);
        if (title == null) {
            callback.onArt(key, null);
            return;
        }
        Bitmap cached = MEMORY.get(key);
        if (cached != null) {
            callback.onArt(key, cached);
            return;
        }
        if (NOT_FOUND.contains(key)) {
            callback.onArt(key, null);
            return;
        }
        final File dir = new File(context.getCacheDir(), "capas-v2");
        final File oldDir = new File(context.getCacheDir(), "capas");
        EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try {
                // Apaga as capas da versão antiga (algumas estavam erradas)
                if (oldDir.exists()) {
                    File[] old = oldDir.listFiles();
                    if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    //noinspection ResultOfMethodCallIgnored
                    oldDir.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File file = new File(dir, Integer.toHexString(key.hashCode()) + ".jpg");
                if (file.exists()) {
                    bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                }
                if (bitmap == null) {
                    String url = findArtwork(artist, title);
                    if (url != null) {
                        byte[] bytes = download(url);
                        if (bytes != null) {
                            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            if (bitmap != null) {
                                try (FileOutputStream out = new FileOutputStream(file)) {
                                    out.write(bytes);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                bitmap = null;
            }
            final Bitmap result = bitmap;
            MAIN.post(() -> {
                if (result != null) MEMORY.put(key, result);
                else NOT_FOUND.add(key); // não tenta outra vez nesta sessão
                callback.onArt(key, result);
            });
        });
    }

    /** Se a internet voltar, permite tentar outra vez as capas que falharam. */
    public static void forgetFailures() {
        NOT_FOUND.clear();
    }

    /**
     * Procura a capa no Deezer (boa cobertura de música portuguesa) e depois no iTunes
     * de Portugal e dos EUA. Só aceita um resultado se o artista e a música baterem certo:
     * mais vale a capa genérica do que uma capa errada.
     */
    private static String findArtwork(String artist, String title) {
        String t = cleanTitle(title);
        String a = artist == null ? null : mainArtist(artist);
        String url = null;
        try {
            if (a != null) url = deezer("artist:\"" + a + "\" track:\"" + t + "\"", a, t);
            if (url == null) url = deezer((a == null ? "" : a + " ") + t, a, t);
        } catch (Exception ignored) {
        }
        if (url == null) url = itunes(a, t, "PT");
        if (url == null) url = itunes(a, t, "US");
        return url;
    }

    private static String deezer(String query, String artist, String title) throws Exception {
        byte[] body = download("https://api.deezer.com/search?limit=15&q=" + URLEncoder.encode(query.trim(), "UTF-8"));
        if (body == null) return null;
        JSONArray data = new JSONObject(new String(body, "UTF-8")).optJSONArray("data");
        if (data == null) return null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject track = data.getJSONObject(i);
            JSONObject art = track.optJSONObject("artist");
            JSONObject album = track.optJSONObject("album");
            if (art == null || album == null) continue;
            if (matches(artist, title, art.optString("name"), track.optString("title"))) {
                String cover = album.optString("cover_xl", album.optString("cover_big", ""));
                if (!cover.isEmpty()) return cover;
            }
        }
        return null;
    }

    private static String itunes(String artist, String title, String country) {
        try {
            String term = (artist == null ? "" : artist + " ") + title;
            byte[] body = download("https://itunes.apple.com/search?media=music&entity=song&limit=15&country="
                    + country + "&term=" + URLEncoder.encode(term.trim(), "UTF-8"));
            if (body == null) return null;
            JSONArray results = new JSONObject(new String(body, "UTF-8")).optJSONArray("results");
            if (results == null) return null;
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                if (matches(artist, title, r.optString("artistName"), r.optString("trackName"))) {
                    String art = r.optString("artworkUrl100", "");
                    if (!art.isEmpty()) return art.replace("100x100bb", "600x600bb");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** O resultado é mesmo esta música deste artista? */
    static boolean matches(String wantedArtist, String wantedTitle, String foundArtist, String foundTitle) {
        String wt = normalize(cleanTitle(wantedTitle));
        String ft = normalize(cleanTitle(foundTitle));
        if (wt.isEmpty() || ft.isEmpty()) return false;
        boolean titleOk = wt.equals(ft) || (wt.length() >= 4 && ft.length() >= 4 && (wt.startsWith(ft) || ft.startsWith(wt)));
        if (!titleOk) return false;
        if (wantedArtist == null) return wt.equals(ft); // sem artista, só aceita o nome exato
        String wa = normalize(wantedArtist);
        String fa = normalize(foundArtist);
        if (wa.isEmpty() || fa.isEmpty()) return false;
        if (fa.contains(wa) || wa.contains(fa)) return true;
        for (String part : foundArtist.split(",|&| feat\\.?| ft\\.?| x ")) {
            String p = normalize(part);
            if (!p.isEmpty() && (p.equals(wa) || wa.contains(p))) return true;
        }
        return false;
    }

    /** Primeiro artista de "A feat. B", "A, B" ou "A & B". */
    static String mainArtist(String artist) {
        String a = artist.split("(?i)\\s+(feat\\.?|ft\\.?|featuring|x)\\s+|,|&")[0].trim();
        return a.isEmpty() ? artist.trim() : a;
    }

    /** minúsculas, sem acentos nem pontuação: "Sala de Audiências!" → "sala de audiencias" */
    static String normalize(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return n.replaceAll("\\s+", " ");
    }

    /** Tira coisas como "(Remastered 2011)" ou "- Live" que atrapalham a pesquisa. */
    private static String cleanTitle(String title) {
        String t = title.replaceAll("\\s*[(\\[].*?[)\\]]", "");
        int dash = t.indexOf(" - ");
        if (dash > 0) t = t.substring(0, dash);
        return t.trim().isEmpty() ? title : t.trim();
    }

    private static byte[] download(String address) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "LauncherDoCarro/2.0");
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 4 * 1024 * 1024) return null; // limite de segurança
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
