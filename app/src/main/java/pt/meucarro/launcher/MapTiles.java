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
 * Mapa escuro (CARTO "dark", dados © OpenStreetMap) desenhado por baixo dos percursos.
 * Coordenadas do "mundo" em Web Mercator ao zoom 0 (256 px de lado). Os quadrados do mapa
 * ficam guardados no rádio, por isso um sítio já visto abre sem internet.
 */
public final class MapTiles {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Map<String, Bitmap> MEMORY = new LinkedHashMap<String, Bitmap>(48, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > 40;
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
        return (int) Math.max(2, Math.min(17, Math.floor(Math.log(scale) / Math.log(2))));
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
                Bitmap b = tile(v, zoom, tx, ty);
                if (b == null) continue;
                any = true;
                float x0 = (float) ((tx * tileWorld - left) * scale);
                float y0 = (float) ((ty * tileWorld - top) * scale);
                float size = (float) (tileWorld * scale);
                RECT.set(x0, y0, x0 + size + 0.5f, y0 + size + 0.5f);
                c.drawBitmap(b, null, RECT, PAINT);
            }
        }
        if (any) c.drawColor(0x22000000); // um pouco mais escuro, para as linhas sobressaírem
        return any;
    }

    public static void credits(Canvas c, View v) {
        CREDIT.setColor(0x99C9CDD3);
        CREDIT.setTextSize(13f);
        CREDIT.setTextAlign(Paint.Align.RIGHT);
        c.drawText("© OpenStreetMap · © CARTO", v.getWidth() - 18, v.getHeight() - 14, CREDIT);
    }

    private static Bitmap tile(View v, int z, int x, int y) {
        final String key = z + "_" + x + "_" + y;
        synchronized (MEMORY) {
            Bitmap b = MEMORY.get(key);
            if (b != null) return b;
            if (LOADING.contains(key)) return null;
            LOADING.add(key);
        }
        final File dir = new File(v.getContext().getFilesDir(), "tiles");
        POOL.execute(() -> {
            Bitmap b = null;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, key + ".png");
                if (!f.exists()) {
                    String host = "abcd".charAt(Math.abs(x + y) % 4) + ".basemaps.cartocdn.com";
                    HttpURLConnection con = (HttpURLConnection) new URL(
                            "https://" + host + "/dark_all/" + z + "/" + x + "/" + y + "@2x.png").openConnection();
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
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
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
