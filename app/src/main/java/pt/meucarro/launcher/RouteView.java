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
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double lat0 = points.get(0)[0] / 1e5;
        double k = Math.cos(Math.toRadians(lat0));
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = points.get(i)[1] / 1e5 * k;
            py[i] = -points.get(i)[0] / 1e5;
            minX = Math.min(minX, px[i]);
            maxX = Math.max(maxX, px[i]);
            minY = Math.min(minY, py[i]);
            maxY = Math.max(maxY, py[i]);
        }
        float pad = 44;
        double w = Math.max(1e-6, maxX - minX), h = Math.max(1e-6, maxY - minY);
        double scale = Math.min((getWidth() - 2 * pad) / w, (getHeight() - 2 * pad) / h);
        double offX = (getWidth() - w * scale) / 2, offY = (getHeight() - h * scale) / 2;
        for (int i = 0; i < n; i++) {
            xs[i] = (float) (offX + (px[i] - minX) * scale);
            ys[i] = (float) (offY + (py[i] - minY) * scale);
            colors[i] = colorFor(points.get(i)[2]);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        // pontos de fundo, tipo papel quadriculado
        for (int x = 24; x < getWidth(); x += 32) {
            for (int y = 24; y < getHeight(); y += 32) c.drawCircle(x, y, 1.2f, grid);
        }
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
    }
}
