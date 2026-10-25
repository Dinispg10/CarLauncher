package pt.meucarro.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Mapa de exploração: mapa escuro, os concelhos visitados pintados e todas as estradas percorridas
 * desenhadas a brilhar. Arrastar para mover, dois dedos para aproximar, toque duplo para aproximar,
 * toque simples para saber o nome do concelho.
 */
public class ExploreView extends View {

    public interface TapListener {
        void onTap(Concelhos.Concelho concelho);
    }

    private double[] tx = new double[0], ty = new double[0];
    private final List<Path> paths = new ArrayList<>();
    private final List<android.graphics.RectF> bounds = new ArrayList<>();
    private final List<Concelhos.Concelho> concelhos = new ArrayList<>();
    private Set<String> visited;
    private double ox, oy; // origem dos contornos (melhor precisão)
    private double left, top, scale = 0;
    private int accent = 0xFF5EEAD4;
    private TapListener tapListener;
    private boolean anyTile;
    private float[] buf = new float[4096];

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    public ExploreView(Context context) {
        super(context);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(0); // linha fina de 1 píxel, seja qual for o zoom
        for (Paint p : new Paint[]{glow, line}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        glow.setStrokeWidth(8f);
        line.setStrokeWidth(2.6f);

        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAt(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                left += dx / scale;
                top += dy / scale;
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAt(2f, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener == null) return false;
                double wx = left + e.getX() / scale, wy = top + e.getY() / scale;
                double lon = wx / 256.0 * 360.0 - 180.0;
                double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * wy / 256.0))));
                tapListener.onTap(Concelhos.find(getContext(), lat, lon));
                return true;
            }
        });
    }

    public void setTapListener(TapListener l) {
        tapListener = l;
    }

    public void setAccent(int color) {
        accent = color;
        invalidate();
    }

    /** Contornos dos concelhos (uma vez) e quais já foram visitados. */
    public void setConcelhos(List<Concelhos.Concelho> list, Set<String> visitedKeys) {
        visited = visitedKeys;
        if (paths.isEmpty()) {
            ox = MapTiles.wx(-8.2);
            oy = MapTiles.wy(39.6);
            for (Concelhos.Concelho k : list) {
                Path p = new Path();
                for (int[] r : k.rings) {
                    for (int i = 0; i < r.length / 2; i++) {
                        float x = (float) (MapTiles.wx(r[i * 2] / 1e4) - ox);
                        float y = (float) (MapTiles.wy(r[i * 2 + 1] / 1e4) - oy);
                        if (i == 0) p.moveTo(x, y);
                        else p.lineTo(x, y);
                    }
                    p.close();
                }
                paths.add(p);
                android.graphics.RectF b = new android.graphics.RectF();
                p.computeBounds(b, true);
                bounds.add(b);
                concelhos.add(k);
            }
        }
        invalidate();
    }

    public void setTrack(double[] x, double[] y) {
        tx = x;
        ty = y;
        if (scale == 0) fitTrackOrPortugal();
        invalidate();
    }

    public boolean hasTrack() {
        return tx.length > 1;
    }

    /** Mostra todas as estradas percorridas (ou Portugal, se ainda não houver nenhuma). */
    public void fitTrackOrPortugal() {
        double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
        for (int i = 0; i < tx.length; i++) {
            if (Double.isNaN(tx[i])) continue;
            minX = Math.min(minX, tx[i]);
            maxX = Math.max(maxX, tx[i]);
            minY = Math.min(minY, ty[i]);
            maxY = Math.max(maxY, ty[i]);
        }
        if (maxX < minX) {
            fitPortugal();
            return;
        }
        // nunca mais perto do que ~15 km de largura
        double minSpan = MapTiles.wx(0.15) - MapTiles.wx(0);
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        double w = Math.max(minSpan, maxX - minX), h = Math.max(minSpan * 0.6, maxY - minY);
        fit(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
    }

    public void fitPortugal() {
        fit(MapTiles.wx(-9.6), MapTiles.wy(42.2), MapTiles.wx(-6.1), MapTiles.wy(36.9));
    }

    private void fit(double x0, double y0, double x1, double y1) {
        if (getWidth() == 0) {
            post(() -> fit(x0, y0, x1, y1));
            return;
        }
        float pad = 40;
        scale = Math.min((getWidth() - 2 * pad) / (x1 - x0), (getHeight() - 2 * pad) / (y1 - y0));
        left = (x0 + x1) / 2 - getWidth() / 2.0 / scale;
        top = (y0 + y1) / 2 - getHeight() / 2.0 / scale;
        invalidate();
    }

    private void zoomAt(float factor, float fx, float fy) {
        double wx = left + fx / scale, wy = top + fy / scale;
        scale = Math.max(60, Math.min(Math.pow(2, 18), scale * factor));
        left = wx - fx / scale;
        top = wy - fy / scale;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean a = scaler.onTouchEvent(e);
        boolean b = scaler.isInProgress() || gestures.onTouchEvent(e);
        return a || b;
    }

    private void drawConcelhos(Canvas c) {
        c.save();
        c.translate((float) ((ox - left) * scale), (float) ((oy - top) * scale));
        c.scale((float) scale, (float) scale);
        float vl = (float) (left - ox), vt = (float) (top - oy);
        float vr = vl + (float) (getWidth() / scale), vb = vt + (float) (getHeight() / scale);
        for (int i = 0; i < paths.size(); i++) {
            android.graphics.RectF b = bounds.get(i);
            if (b.right < vl || b.left > vr || b.bottom < vt || b.top > vb) continue;
            boolean seen = visited != null && visited.contains(concelhos.get(i).key());
            if (seen) {
                fill.setColor((accent & 0x00FFFFFF) | 0x2E000000);
                c.drawPath(paths.get(i), fill);
            }
            border.setColor(seen ? (accent & 0x00FFFFFF) | 0x99000000 : 0x24FFFFFF);
            c.drawPath(paths.get(i), border);
        }
        c.restore();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (scale == 0) return;
        c.drawColor(0xFF0E1013);
        anyTile = MapTiles.draw(c, this, left, top, scale);

        // Concelhos: visitados pintados com a cor de destaque, os outros só com o contorno.
        // Muito perto (ruas) não se desenham: ficavam maiores do que o rádio consegue desenhar.
        if (scale < 16000) drawConcelhos(c);


        // Estradas percorridas (salta pontos a menos de 2 píxeis uns dos outros)
        int n = 0;
        float px = 0, py = 0;
        boolean have = false;
        for (int i = 0; i < tx.length; i++) {
            if (Double.isNaN(tx[i])) {
                have = false;
                continue;
            }
            float x = (float) ((tx[i] - left) * scale), y = (float) ((ty[i] - top) * scale);
            if (have) {
                float dx = x - px, dy = y - py;
                if (dx * dx + dy * dy < 4f) continue;
                boolean visible = !(x < -50 && px < -50 || y < -50 && py < -50
                        || x > getWidth() + 50 && px > getWidth() + 50 || y > getHeight() + 50 && py > getHeight() + 50);
                if (visible && dx * dx + dy * dy < 250000) { // ignora saltos enormes
                    if (n + 4 > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    buf[n++] = px;
                    buf[n++] = py;
                    buf[n++] = x;
                    buf[n++] = y;
                }
            }
            px = x;
            py = y;
            have = true;
        }
        glow.setColor((accent & 0x00FFFFFF) | 0x40000000);
        line.setColor(accent);
        c.drawLines(buf, 0, n, glow);
        c.drawLines(buf, 0, n, line);
        if (anyTile) MapTiles.credits(c, this);
    }
}
