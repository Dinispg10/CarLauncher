package pt.meucarro.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Músicas guardadas no próprio rádio (para quando o telemóvel não está ligado).
 * Ficam na pasta do Launcher (Android/data/pt.meucarro.launcher/files/Music), enviadas pela página
 * do rádio; também lê pens USB / cartões SD se o Android deixar.
 * Os nomes, álbuns e capas vêm das etiquetas dos próprios ficheiros.
 */
public final class OfflineMusic {

    public static final class Track {
        public String path, title, artist, album;
        public int number;
        public long durationMs;
    }

    public static final class Album {
        public String key, name, artist;
        public final List<Track> tracks = new ArrayList<>();

        public long durationMs() {
            long d = 0;
            for (Track t : tracks) d += t.durationMs;
            return d;
        }
    }

    private static final String[] EXT = {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav", ".wma"};

    private static List<Album> albums;
    private static List<Track> all;

    private OfflineMusic() {
    }

    /** Pasta onde a página do rádio guarda as músicas enviadas. */
    public static File folder(Context c) {
        File d = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (d == null) d = new File(c.getFilesDir(), "Music");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : EXT) if (n.endsWith(e)) return true;
        return false;
    }

    /** Volta a ler as pastas na próxima vez (depois de enviar ou apagar músicas). */
    public static synchronized void invalidate() {
        albums = null;
        all = null;
    }

    public static synchronized List<Album> albums(Context c) {
        if (albums == null) scan(c);
        return albums;
    }

    public static synchronized List<Track> allTracks(Context c) {
        if (all == null) scan(c);
        return all;
    }

    public static synchronized Track find(Context c, String path) {
        for (Track t : allTracks(c)) if (t.path.equals(path)) return t;
        return null;
    }

    // ---------- Ler as pastas ----------

    private static void scan(Context c) {
        List<File> files = new ArrayList<>();
        collect(folder(c), files, 0);
        // Pens USB e cartões SD (só se o Android der autorização de leitura)
        if (c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            for (File root : externalRoots()) collect(root, files, 0);
        }
        // Índice guardado: não volta a abrir os ficheiros que já conhece (é lento)
        File idxFile = new File(c.getFilesDir(), "offline_index.json");
        Map<String, JSONObject> idx = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(read(idxFile));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                idx.put(o.getString("p"), o);
            }
        } catch (Exception ignored) {
        }
        JSONArray out = new JSONArray();
        List<Track> tracks = new ArrayList<>();
        for (File f : files) {
            String sig = f.length() + ":" + f.lastModified();
            JSONObject o = idx.get(f.getAbsolutePath());
            if (o == null || !sig.equals(o.optString("sig"))) o = readTags(f, sig);
            out.put(o);
            Track t = new Track();
            t.path = f.getAbsolutePath();
            t.title = o.optString("t", null);
            t.artist = o.optString("a", null);
            t.album = o.optString("al", null);
            t.number = o.optInt("n", 0);
            t.durationMs = o.optLong("d", 0);
            if (t.title == null || t.title.isEmpty()) t.title = niceName(f.getName());
            if (t.album != null && t.album.isEmpty()) t.album = null;
            if (t.artist != null && t.artist.isEmpty()) t.artist = null;
            tracks.add(t);
        }
        try (FileOutputStream fo = new FileOutputStream(idxFile)) {
            fo.write(out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }

        // Junta por álbum (músicas sem álbum ficam em "Músicas soltas")
        Map<String, Album> map = new LinkedHashMap<>();
        for (Track t : tracks) {
            String key = t.album == null ? "~soltas" : t.album.toLowerCase(Locale.ROOT);
            Album a = map.get(key);
            if (a == null) {
                a = new Album();
                a.key = key;
                a.name = t.album == null ? "Músicas soltas" : t.album;
                map.put(key, a);
            }
            a.tracks.add(t);
        }
        List<Album> list = new ArrayList<>(map.values());
        for (Album a : list) {
            Collections.sort(a.tracks, (x, y) -> x.number != y.number && x.number > 0 && y.number > 0
                    ? Integer.compare(x.number, y.number) : x.path.compareToIgnoreCase(y.path));
            // Artista do álbum: o mais comum
            Map<String, Integer> count = new HashMap<>();
            for (Track t : a.tracks) if (t.artist != null) count.put(t.artist, count.containsKey(t.artist) ? count.get(t.artist) + 1 : 1);
            int best = 0;
            for (Map.Entry<String, Integer> e : count.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    a.artist = e.getKey();
                }
            }
            if (count.size() > 1 && best < a.tracks.size() * 0.6) a.artist = "Vários artistas";
        }
        Collections.sort(list, (x, y) -> {
            if (x.key.equals("~soltas")) return 1;
            if (y.key.equals("~soltas")) return -1;
            String ax = x.artist == null ? "" : x.artist, ay = y.artist == null ? "" : y.artist;
            int r = ax.compareToIgnoreCase(ay);
            return r != 0 ? r : x.name.compareToIgnoreCase(y.name);
        });
        List<Track> ordered = new ArrayList<>();
        for (Album a : list) ordered.addAll(a.tracks);
        albums = list;
        all = ordered;
    }

    private static List<File> externalRoots() {
        List<File> out = new ArrayList<>();
        File[] mounts = new File("/storage").listFiles();
        if (mounts != null) {
            for (File m : mounts) {
                String n = m.getName();
                if (n.equals("emulated") || n.equals("self")) continue;
                if (m.canRead()) out.add(m);
            }
        }
        File music = new File(Environment.getExternalStorageDirectory(), "Music");
        if (music.canRead()) out.add(music);
        return out;
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (depth > 6 || out.size() > 5000) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (f.getName().equals("Android") && depth == 0 && !dir.getAbsolutePath().contains("Android/data")) continue;
                collect(f, out, depth + 1);
            } else if (isAudio(f.getName()) && f.length() > 50000) {
                out.add(f);
            }
        }
    }

    private static JSONObject readTags(File f, String sig) {
        JSONObject o = new JSONObject();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            o.put("p", f.getAbsolutePath()).put("sig", sig);
            r.setDataSource(f.getAbsolutePath());
            o.put("t", nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)));
            String artist = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (artist.isEmpty()) artist = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            o.put("a", artist);
            o.put("al", nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)));
            String n = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
            if (n.contains("/")) n = n.substring(0, n.indexOf('/'));
            try {
                o.put("n", Integer.parseInt(n.trim()));
            } catch (Exception ignored) {
            }
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) o.put("d", Long.parseLong(d));
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** "03 - Artista - Nome da música.mp3" → "Nome da música" (quando o ficheiro não tem etiquetas). */
    static String niceName(String file) {
        String n = file.replaceAll("\\.[A-Za-z0-9]{2,4}$", "").replace('_', ' ').trim();
        n = n.replaceFirst("^\\d{1,3}\\s*[-.\\s]\\s*", "");
        int dash = n.lastIndexOf(" - ");
        if (dash > 0 && dash < n.length() - 3) n = n.substring(dash + 3);
        return n.trim();
    }

    // ---------- Capas ----------

    private static final Map<String, Bitmap> ART = new LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > 40;
        }
    };

    /** Capa do ficheiro (guardada em cache), ou null se não tiver. Pode ser lento: não chamar no fio principal. */
    public static Bitmap art(Context c, String path, int size) {
        // A chave inclui o tamanho e a data do ficheiro: se a capa for trocada (ficheiro reenviado), lê a nova
        File src = new File(path);
        String key = path + "@" + size + "@" + src.length() + "@" + src.lastModified();
        synchronized (ART) {
            if (ART.containsKey(key)) return ART.get(key);
        }
        Bitmap b = null;
        File cache = new File(c.getCacheDir(), "offline_art/" + Integer.toHexString(key.hashCode()) + ".jpg");
        if (cache.exists()) {
            b = BitmapFactory.decodeFile(cache.getAbsolutePath());
        } else {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(path);
                byte[] pic = r.getEmbeddedPicture();
                if (pic == null) pic = folderImage(new File(path).getParentFile());
                if (pic != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                    int sample = 1;
                    while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2;
                    o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    Bitmap full = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                    if (full != null) {
                        b = Bitmap.createScaledBitmap(full, size, size, true);
                        //noinspection ResultOfMethodCallIgnored
                        cache.getParentFile().mkdirs();
                        try (FileOutputStream fo = new FileOutputStream(cache)) {
                            b.compress(Bitmap.CompressFormat.JPEG, 88, fo);
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
        }
        synchronized (ART) {
            ART.put(key, b);
        }
        return b;
    }

    /** cover.jpg / folder.jpg na pasta do álbum. */
    private static byte[] folderImage(File dir) {
        if (dir == null) return null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if ((n.startsWith("cover") || n.startsWith("folder") || n.startsWith("front")) && (n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".jpeg"))) {
                try {
                    return readBytes(f);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    // ---------- Letras ----------

    private static boolean prefetching;

    /**
     * Vai buscar (com internet) as letras de todas as músicas offline e guarda-as no rádio,
     * para a ♪ Letra funcionar depois sem internet. As que já estão guardadas não voltam a ser pedidas.
     */
    public static void prefetchLyrics(Context c) {
        final Context app = c.getApplicationContext();
        synchronized (OfflineMusic.class) {
            if (prefetching) return;
            prefetching = true;
        }
        new Thread(() -> {
            try {
                for (Track t : new ArrayList<>(allTracks(app))) {
                    if (t.durationMs <= 0 || t.artist == null) continue;
                    final Object lock = new Object();
                    final boolean[] done = {false};
                    final boolean[] offline = {false};
                    Lyrics.fetch(app, t.artist, t.title, t.album, t.durationMs, (k, r) -> {
                        synchronized (lock) {
                            done[0] = true;
                            offline[0] = r.offline;
                            lock.notifyAll();
                        }
                    });
                    synchronized (lock) {
                        while (!done[0]) lock.wait(30000);
                    }
                    if (offline[0]) break; // sem internet: fica para a próxima
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (OfflineMusic.class) {
                    prefetching = false;
                }
            }
        }, "Letras").start();
    }

    // ---------- Apagar ----------

    /** Apaga um álbum (só os ficheiros enviados para o rádio; numa pen não mexe). */
    public static int deleteAlbum(Context c, String key) {
        String mine = folder(c).getAbsolutePath();
        int n = 0;
        for (Album a : albums(c)) {
            if (!a.key.equals(key)) continue;
            for (Track t : a.tracks) {
                if (t.path.startsWith(mine) && new File(t.path).delete()) n++;
            }
        }
        invalidate();
        return n;
    }

    public static long usedBytes(Context c) {
        long n = 0;
        List<File> files = new ArrayList<>();
        collect(folder(c), files, 0);
        for (File f : files) n += f.length();
        return n;
    }

    private static String read(File f) throws Exception {
        return new String(readBytes(f), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return b;
        }
    }
}
