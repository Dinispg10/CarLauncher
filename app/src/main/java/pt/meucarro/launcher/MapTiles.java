package pt.meucarro.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mapa escuro (Esri "Dark Gray Canvas": fundo + nomes das ruas) desenhado por baixo dos percursos.
 * (O CARTO passou a pedir chave; o da Esri não.)
 * Coordenadas do "mundo" em Web Mercator ao zoom 0 (256 px de lado). Os quadrados do mapa
 * ficam guardados no rádio, por isso um sítio já visto abre sem internet.
 */
public final class MapTiles {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Map<String, Bitmap> MEMORY = new LinkedHashMap<String, Bitmap>(48, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > 60;
        }
    };
    private static final Set<String> LOADING = new HashSet<>();
    private static final Paint PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint CREDIT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF RECT = new RectF();

    private MapTiles() {
    }

    public static double wx(double lon) {
        return (lon + 180.0) / 360.0 * 256.0;
    }

    public static double wy(double lat) {
        double r = Math.toRadians(Math.max(-85, Math.min(85, lat)));
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * 256.0;
    }

    /** Zoom dos quadrados para uma escala (píxeis do ecrã por unidade do mundo). */
    public static int zoomFor(double scale) {
        return (int) Math.max(2, Math.min(16, Math.floor(Math.log(scale) / Math.log(2))));
    }

    /**
     * Desenha os quadrados do mapa. left/top = coordenadas do mundo no canto do ecrã.
     * Devolve true se desenhou pelo menos um (para mostrar os créditos).
     */
    public static boolean draw(Canvas c, View v, double left, double top, double scale) {
        int zoom = zoomFor(scale);
        double tileWorld = 256.0 / (1 << zoom);
        double right = left + v.getWidth() / scale, bottom = top + v.getHeight() / scale;
        int max = (1 << zoom) - 1;
        boolean any = false;
        for (int tx = (int) Math.floor(left / tileWorld); tx <= (int) Math.floor(right / tileWorld); tx++) {
            for (int ty = (int) Math.floor(top / tileWorld); ty <= (int) Math.floor(bottom / tileWorld); ty++) {
                if (tx < 0 || ty < 0 || tx > max || ty > max) continue;
                float x0 = (float) ((tx * tileWorld - left) * scale);
                float y0 = (float) ((ty * tileWorld - top) * scale);
                float size = (float) (tileWorld * scale);
                RECT.set(x0, y0, x0 + size + 0.5f, y0 + size + 0.5f);
                for (int layer = 0; layer < LAYERS.length; layer++) {
                    Bitmap b = tile(v, layer, zoom, tx, ty);
                    if (b == null) continue;
                    if (layer == 0) any = true;
                    c.drawBitmap(b, null, RECT, PAINT);
                }
            }
        }
        return any;
    }

    public static void credits(Canvas c, View v) {
        CREDIT.setColor(0x99C9CDD3);
        CREDIT.setTextSize(13f);
        CREDIT.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Esri, HERE, Garmin, © OpenStreetMap", v.getWidth() - 18, v.getHeight() - 14, CREDIT);
    }

    /** Camadas: fundo escuro e, por cima, os nomes (transparente). */
    private static final String[] LAYERS = {
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/",
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/",
    };
    private static boolean cleaned;

    private static Bitmap tile(View v, int layer, int z, int x, int y) {
        final String key = layer + "_" + z + "_" + x + "_" + y;
        synchronized (MEMORY) {
            Bitmap b = MEMORY.get(key);
            if (b != null) return b;
            if (LOADING.contains(key)) return null;
            LOADING.add(key);
        }
        final File dir = new File(v.getContext().getFilesDir(), "tiles_esri");
        final File old = new File(v.getContext().getFilesDir(), "tiles");
        POOL.execute(() -> {
            if (!cleaned) {
                // apaga os quadrados antigos do CARTO ("API KEY REQUIRED")
                cleaned = true;
                File[] fs = old.listFiles();
                if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
            Bitmap b = null;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, key + ".png");
                // Quadrados com mais de 90 dias voltam a ser descarregados (estradas novas)
                boolean old90 = f.exists() && System.currentTimeMillis() - f.lastModified() > 90L * 24 * 3600 * 1000;
                if (!f.exists() || old90) {
                    HttpURLConnection con = (HttpURLConnection) new URL(
                            LAYERS[layer] + z + "/" + y + "/" + x).openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(10000);
                    con.setRequestProperty("User-Agent", "LauncherDoCarro/1.0 (Android)");
                    if (con.getResponseCode() == 200) {
                        File tmp = new File(dir, key + ".tmp");
                        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        //noinspection ResultOfMethodCallIgnored
                        tmp.renameTo(f);
                    }
                    con.disconnect();
                }
                if (f.exists()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = layer == 0 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
                    b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                }
            } catch (Exception ignored) {
            }
            synchronized (MEMORY) {
                if (b != null) MEMORY.put(key, b);
                LOADING.remove(key); // sem internet: tenta outra vez da próxima vez
            }
            if (b != null) v.postInvalidate();
        });
        return null;
    }
}
