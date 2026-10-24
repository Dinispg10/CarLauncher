package pt.meucarro.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Desenha o percurso de uma viagem como uma linha luminosa sobre fundo escuro.
 * Cor pela velocidade: azul (até 50 km/h), verde (50–90), laranja (mais de 90).
 * replay() faz a linha "voltar a fazer-se" do início ao fim.
 */
public class RouteView extends View {

    public static final int SLOW = 0xFF60A5FA;
    public static final int MID = 0xFF4ADE80;
    public static final int FAST = 0xFFFB923C;

    private float[] xs = new float[0], ys = new float[0];
    private int[] colors = new int[0];
    private List<int[]> points = new ArrayList<>();
    private float progress = 1f;
    private ValueAnimator animator;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RouteView(Context context) {
        super(context);
        for (Paint p : new Paint[]{glow, line}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        glow.setStrokeWidth(14f);
        line.setStrokeWidth(4.5f);
        grid.setColor(0xFF1C2027);
    }

    public static int colorFor(int kmh) {
        return kmh < 50 ? SLOW : kmh <= 90 ? MID : FAST;
    }

    public boolean isEmpty() {
        return points.size() < 2;
    }

    /** Mostra o percurso; animate=true desenha-o aos poucos. */
    public void setRoute(List<int[]> route, boolean animate) {
        // Para não pesar no rádio, desenha no máximo ~1500 pontos
        List<int[]> pts = new ArrayList<>();
        int step = Math.max(1, route.size() / 1500);
        for (int i = 0; i < route.size(); i += step) pts.add(route.get(i));
        if (!route.isEmpty() && pts.get(pts.size() - 1) != route.get(route.size() - 1)) pts.add(route.get(route.size() - 1));
        points = pts;
        project();
        if (animate) replay();
        else invalidate();
    }

    public void replay() {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(Math.min(5000, 1800 + points.size() * 3L));
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        progress = 0f;
        animator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        project();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    // Projeção (Web Mercator, igual aos mapas): coordenadas do "mundo" ao zoom 0 (256 px)
    private double minWx, minWy, scale, offX, offY;
    private int zoom;

    private static double wx(double lon) {
        return (lon + 180.0) / 360.0 * 256.0;
    }

    private static double wy(double lat) {
        double r = Math.toRadians(Math.max(-85, Math.min(85, lat)));
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * 256.0;
    }

    /** Converte latitude/longitude para o ecrã, a caber com margem e sem deformar. */
    private void project() {
        int n = points.size();
        if (n < 2 || getWidth() == 0) {
            xs = new float[0];
            ys = new float[0];
            colors = new int[0];
            return;
        }
        xs = new float[n];
        ys = new float[n];
        colors = new int[n];
        double[] px = new double[n], py = new double[n];
        double maxWx = -1e9, maxWy = -1e9;
        minWx = 1e9;
        minWy = 1e9;
        for (int i = 0; i < n; i++) {
            px[i] = wx(points.get(i)[1] / 1e5);
            py[i] = wy(points.get(i)[0] / 1e5);
            minWx = Math.min(minWx, px[i]);
            maxWx = Math.max(maxWx, px[i]);
            minWy = Math.min(minWy, py[i]);
            maxWy = Math.max(maxWy, py[i]);
        }
        float pad = 56;
        double w = Math.max(1e-9, maxWx - minWx), h = Math.max(1e-9, maxWy - minWy);
        scale = Math.min((getWidth() - 2 * pad) / w, (getHeight() - 2 * pad) / h);
        scale = Math.min(scale, Math.pow(2, 17) * 1.6); // viagens muito curtas: não aproxima demais
        zoom = (int) Math.max(2, Math.min(17, Math.floor(Math.log(scale) / Math.log(2))));
        offX = (getWidth() - w * scale) / 2;
        offY = (getHeight() - h * scale) / 2;
        for (int i = 0; i < n; i++) {
            xs[i] = (float) (offX + (px[i] - minWx) * scale);
            ys[i] = (float) (offY + (py[i] - minWy) * scale);
            colors[i] = colorFor(points.get(i)[2]);
        }
    }

    // ---------- Mapa por trás (CARTO "dark", dados OpenStreetMap; guardado no rádio) ----------

    private static final java.util.concurrent.ExecutorService TILES =
            java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final java.util.Map<String, android.graphics.Bitmap> MEMORY =
            new java.util.LinkedHashMap<String, android.graphics.Bitmap>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Bitmap> e) {
                    return size() > 30;
                }
            };
    private static final java.util.Set<String> LOADING = new java.util.HashSet<>();
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint credit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF rect = new android.graphics.RectF();
    private boolean anyTile;

    private void drawMap(Canvas c) {
        anyTile = false;
        if (xs.length < 2) return;
        double tileWorld = 256.0 / (1 << zoom);
        double left = minWx - offX / scale, top = minWy - offY / scale;
        double right = left + getWidth() / scale, bottom = top + getHeight() / scale;
        int max = (1 << zoom) - 1;
        for (int tx = (int) Math.floor(left / tileWorld); tx <= (int) Math.floor(right / tileWorld); tx++) {
            for (int ty = (int) Math.floor(top / tileWorld); ty <= (int) Math.floor(bottom / tileWorld); ty++) {
                if (tx < 0 || ty < 0 || tx > max || ty > max) continue;
                android.graphics.Bitmap b = tile(zoom, tx, ty);
                if (b == null) continue;
                anyTile = true;
                float x0 = (float) (offX + (tx * tileWorld - minWx) * scale);
                float y0 = (float) (offY + (ty * tileWorld - minWy) * scale);
                float size = (float) (tileWorld * scale);
                rect.set(x0, y0, x0 + size + 0.5f, y0 + size + 0.5f);
                c.drawBitmap(b, null, rect, tilePaint);
            }
        }
        if (anyTile) c.drawColor(0x22000000); // um pouco mais escuro, para a linha sobressair
    }

    private android.graphics.Bitmap tile(int z, int x, int y) {
        final String key = z + "_" + x + "_" + y;
        synchronized (MEMORY) {
            android.graphics.Bitmap b = MEMORY.get(key);
            if (b != null) return b;
            if (LOADING.contains(key)) return null;
            LOADING.add(key);
        }
        final java.io.File dir = new java.io.File(getContext().getFilesDir(), "tiles");
        TILES.execute(() -> {
            android.graphics.Bitmap b = null;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, key + ".png");
                if (!f.exists()) {
                    String host = "abcd".charAt((x + y) % 4) + ".basemaps.cartocdn.com";
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(
                            "https://" + host + "/dark_all/" + z + "/" + x + "/" + y + "@2x.png").openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(10000);
                    con.setRequestProperty("User-Agent", "LauncherDoCarro/1.0 (Android)");
                    if (con.getResponseCode() == 200) {
                        java.io.File tmp = new java.io.File(dir, key + ".tmp");
                        try (java.io.InputStream in = con.getInputStream();
                             java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
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
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                    b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                }
            } catch (Exception ignored) {
            }
            final android.graphics.Bitmap result = b;
            synchronized (MEMORY) {
                if (result != null) MEMORY.put(key, result);
                // sem internet: tenta outra vez da próxima vez que o ecrã abrir
                LOADING.remove(key);
            }
            if (result != null) postInvalidate();
        });
        return null;
    }

    @Override
    protected void onDraw(Canvas c) {
        // pontos de fundo, tipo papel quadriculado (ficam tapados pelo mapa quando há internet)
        for (int x = 24; x < getWidth(); x += 32) {
            for (int y = 24; y < getHeight(); y += 32) c.drawCircle(x, y, 1.2f, grid);
        }
        drawMap(c);
        int n = xs.length;
        if (n < 2) return;
        float upto = progress * (n - 1);
        int last = (int) Math.floor(upto);
        for (int pass = 0; pass < 2; pass++) {
            Paint p = pass == 0 ? glow : line;
            for (int i = 0; i < last && i < n - 1; i++) {
                int col = colors[i + 1];
                p.setColor(pass == 0 ? (col & 0x00FFFFFF) | 0x38000000 : col);
                c.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1], p);
            }
            // pedaço final (a meio de um segmento)
            if (last < n - 1) {
                float f = upto - last;
                float hx = xs[last] + (xs[last + 1] - xs[last]) * f;
                float hy = ys[last] + (ys[last + 1] - ys[last]) * f;
                int col = colors[last + 1];
                p.setColor(pass == 0 ? (col & 0x00FFFFFF) | 0x38000000 : col);
                c.drawLine(xs[last], ys[last], hx, hy, p);
            }
        }
        // início: anel
        dot.setStyle(Paint.Style.STROKE);
        dot.setStrokeWidth(3f);
        dot.setColor(0xFFF2F3F5);
        c.drawCircle(xs[0], ys[0], 8f, dot);
        dot.setStyle(Paint.Style.FILL);
        // ponta: bola branca com halo
        float hx, hy;
        int headColor;
        if (last >= n - 1) {
            hx = xs[n - 1];
            hy = ys[n - 1];
            headColor = colors[n - 1];
        } else {
            float f = upto - last;
            hx = xs[last] + (xs[last + 1] - xs[last]) * f;
            hy = ys[last] + (ys[last + 1] - ys[last]) * f;
            headColor = colors[last + 1];
        }
        dot.setColor((headColor & 0x00FFFFFF) | 0x55000000);
        c.drawCircle(hx, hy, 16f, dot);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(hx, hy, 7f, dot);
        if (anyTile) {
            credit.setColor(0x99C9CDD3);
            credit.setTextSize(13f);
            credit.setTextAlign(Paint.Align.RIGHT);
            c.drawText("© OpenStreetMap · © CARTO", getWidth() - 18, getHeight() - 14, credit);
        }
    }
}
