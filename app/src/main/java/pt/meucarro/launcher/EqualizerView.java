package pt.meucarro.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Barras animadas tipo equalizador. A tocar: barras a saltar ao ritmo;
 * a ligar: uma onda suave; parado: barras baixas e quietas.
 * (É uma animação, não lê o som: assim não precisa de autorização do microfone.)
 */
public class EqualizerView extends View {

    public enum Mode {PLAYING, LOADING, STOPPED}

    private static final int BARS = 36;

    private final float[] height = new float[BARS];
    private final float[] target = new float[BARS];
    private final float[] speed = new float[BARS];
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Mode mode = Mode.STOPPED;
    private int color = 0xFF5EEAD4;
    private long lastFrame;
    private float time;

    public EqualizerView(Context context) {
        super(context);
        init();
    }

    public EqualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        for (int i = 0; i < BARS; i++) {
            height[i] = 0.06f;
            target[i] = 0.06f;
            speed[i] = 8f + random.nextFloat() * 8f;
        }
    }

    public void setMode(Mode mode) {
        if (this.mode == mode) return;
        this.mode = mode;
        postInvalidateOnAnimation();
    }

    public void setColor(int color) {
        if (this.color == color) return;
        this.color = color;
        updateShader();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateShader();
    }

    private void updateShader() {
        int h = getHeight();
        if (h == 0) return;
        int top = blend(color, 0xFFFFFFFF, 0.35f);
        paint.setShader(new LinearGradient(0, 0, 0, h, top, color, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        time += dt;

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        float gap = w / (float) BARS * 0.38f;
        float barW = (w - gap * (BARS - 1)) / BARS;
        boolean moving = false;

        for (int i = 0; i < BARS; i++) {
            // Forma geral: mais alto ao centro, como num espetro de música
            float profile = 0.55f + 0.45f * (float) Math.sin(Math.PI * (i + 0.5f) / BARS);
            switch (mode) {
                case PLAYING:
                    if (Math.abs(height[i] - target[i]) < 0.04f || random.nextFloat() < dt * 2.5f) {
                        target[i] = (0.12f + random.nextFloat() * 0.88f) * profile;
                    }
                    break;
                case LOADING:
                    target[i] = 0.12f + 0.10f * (float) (1 + Math.sin(time * 4f - i * 0.35f)) * profile;
                    break;
                default:
                    target[i] = 0.05f;
                    break;
            }
            float k = Math.min(1f, dt * speed[i]);
            height[i] += (target[i] - height[i]) * k;
            if (Math.abs(target[i] - height[i]) > 0.003f) moving = true;

            // Barras centradas na vertical, a crescer para cima e para baixo
            float bh = Math.max(barW, height[i] * h);
            float x = i * (barW + gap);
            rect.set(x, (h - bh) / 2f, x + barW, (h + bh) / 2f);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
        }

        if ((mode != Mode.STOPPED || moving) && isShown()) {
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }
}
