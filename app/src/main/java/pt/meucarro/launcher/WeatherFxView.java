package pt.meucarro.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Efeitos do tempo por cima da foto do carro: chuva a cair e gotas no "vidro", neve, trovoada,
 * nevoeiro, sol e noite com estrelas e luzes ao longe. Não recebe toques (passam para a foto).
 * Desenha a ~30 imagens por segundo só quando está visível e com um efeito ligado.
 */
public class WeatherFxView extends View {

    public enum Fx { NONE, SUN, CLOUDS, DRIZZLE, RAIN, STORM, SNOW, FOG }

    private Fx fx = Fx.NONE;
    private boolean night;
    private boolean running;
    private long lastFrame;
    private float t; // segundos desde o início
    private final Random rnd = new Random();

    // Chuva a cair
    private float[] sx, sy, sLen, sSpeed;
    private int[] sAlpha;
    // Gotas no vidro
    private float[] gx, gy, gr, gSlide, gAge, gLife;
    // Neve
    private float[] fx_, fy, fr, fSpeed, fPhase;
    // Estrelas e luzes ao longe
    private float[] stX, stY, stR, stPh;
    private float[] liX, liY, liR, liPh;
    private int[] liColor;
    // Trovoada
    private float flash, nextFlash = 4f;

    private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();

    public WeatherFxView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        streak.setStrokeCap(Paint.Cap.ROUND);
        streak.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
    }

    public void set(Fx effect, boolean isNight) {
        if (effect == fx && isNight == night) return;
        fx = effect;
        night = isNight;
        seed();
        kick();
    }

    public Fx effect() {
        return fx;
    }

    /** Liga/desliga a animação (desliga quando o ecrã não está à vista, para poupar o rádio). */
    public void setRunning(boolean on) {
        running = on;
        if (on) kick();
    }

    private void kick() {
        lastFrame = 0;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        seed();
    }

    private void seed() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        int streaks = fx == Fx.STORM ? 150 : fx == Fx.RAIN ? 110 : fx == Fx.DRIZZLE ? 45 : 0;
        sx = new float[streaks];
        sy = new float[streaks];
        sLen = new float[streaks];
        sSpeed = new float[streaks];
        sAlpha = new int[streaks];
        for (int i = 0; i < streaks; i++) {
            sx[i] = rnd.nextFloat() * (w + 100) - 50;
            sy[i] = rnd.nextFloat() * h;
            sLen[i] = 20 + rnd.nextFloat() * (fx == Fx.DRIZZLE ? 12 : 30);
            sSpeed[i] = (fx == Fx.DRIZZLE ? 650 : 950) + rnd.nextFloat() * 500;
            sAlpha[i] = 70 + rnd.nextInt(fx == Fx.STORM ? 120 : 100);
        }
        int drops = fx == Fx.STORM ? 26 : fx == Fx.RAIN ? 20 : fx == Fx.DRIZZLE ? 12 : 0;
        gx = new float[drops];
        gy = new float[drops];
        gr = new float[drops];
        gSlide = new float[drops];
        gAge = new float[drops];
        gLife = new float[drops];
        for (int i = 0; i < drops; i++) newDrop(i, w, h, true);

        int flakes = fx == Fx.SNOW ? 90 : 0;
        fx_ = new float[flakes];
        fy = new float[flakes];
        fr = new float[flakes];
        fSpeed = new float[flakes];
        fPhase = new float[flakes];
        for (int i = 0; i < flakes; i++) {
            fx_[i] = rnd.nextFloat() * w;
            fy[i] = rnd.nextFloat() * h;
            fr[i] = 1.5f + rnd.nextFloat() * 3.2f;
            fSpeed[i] = 25 + fr[i] * 14 + rnd.nextFloat() * 20;
            fPhase[i] = rnd.nextFloat() * 6.28f;
        }

        boolean clearNight = night && (fx == Fx.NONE || fx == Fx.SUN || fx == Fx.CLOUDS);
        int stars = clearNight ? (fx == Fx.CLOUDS ? 14 : 40) : 0;
        stX = new float[stars];
        stY = new float[stars];
        stR = new float[stars];
        stPh = new float[stars];
        for (int i = 0; i < stars; i++) {
            stX[i] = rnd.nextFloat() * w;
            stY[i] = rnd.nextFloat() * h * 0.32f;
            stR[i] = 0.7f + rnd.nextFloat() * 1.4f;
            stPh[i] = rnd.nextFloat() * 6.28f;
        }
        int lights = 0; // luzes ao longe: ficavam por cima do carro em muitas fotos
        liX = new float[lights];
        liY = new float[lights];
        liR = new float[lights];
        liPh = new float[lights];
        liColor = new int[lights];
        int[] warm = {0xFFFFC870, 0xFFFFE0A0, 0xFFFF9F5A, 0xFFBFD8FF};
        for (int i = 0; i < lights; i++) {
            liX[i] = rnd.nextFloat() * w;
            liY[i] = h * (0.40f + rnd.nextFloat() * 0.14f);
            liR[i] = 5 + rnd.nextFloat() * 9;
            liPh[i] = rnd.nextFloat() * 6.28f;
            liColor[i] = warm[rnd.nextInt(warm.length)];
        }
    }

    private void newDrop(int i, int w, int h, boolean anywhere) {
        gx[i] = rnd.nextFloat() * w;
        gy[i] = anywhere ? rnd.nextFloat() * h : rnd.nextFloat() * h * 0.7f;
        gr[i] = 3 + rnd.nextFloat() * 6;
        gSlide[i] = 0;
        gAge[i] = anywhere ? rnd.nextFloat() * 4 : 0;
        gLife[i] = 2 + rnd.nextFloat() * 6; // tempo parada antes de escorrer
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        t += dt;

        // ---- Noite: céu mais escuro e azulado, estrelas e luzes ao longe ----
        if (night) {
            shade.setShader(new LinearGradient(0, 0, 0, h * 0.75f, 0x9908101E, 0x0008101E, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, shade);
            shade.setShader(null);
            for (int i = 0; i < stX.length; i++) {
                float a = 0.45f + 0.55f * (float) Math.abs(Math.sin(t * 1.3f + stPh[i]));
                fill.setColor(argb((int) (a * 230), 0xFFFFFF));
                c.drawCircle(stX[i], stY[i], stR[i], fill);
            }
            for (int i = 0; i < liX.length; i++) {
                float a = 0.55f + 0.45f * (float) Math.sin(t * 0.8f + liPh[i]);
                int col = liColor[i] & 0xFFFFFF;
                fill.setShader(new RadialGradient(liX[i], liY[i], liR[i] * 2.4f,
                        new int[]{argb((int) (a * 150), col), argb(0, col)}, null, Shader.TileMode.CLAMP));
                c.drawCircle(liX[i], liY[i], liR[i] * 2.4f, fill);
                fill.setShader(null);
            }
        }

        switch (fx) {
            case SUN:
                if (!night) drawSun(c, w, h);
                break;
            case CLOUDS:
                if (!night) drawClouds(c, w, h);
                break;
            case DRIZZLE:
            case RAIN:
            case STORM:
                drawRain(c, w, h, dt);
                break;
            case SNOW:
                drawSnow(c, w, h, dt);
                break;
            case FOG:
                drawFog(c, w, h);
                break;
            default:
                break;
        }

        // ---- Relâmpagos ----
        if (fx == Fx.STORM) {
            nextFlash -= dt;
            if (nextFlash <= 0) {
                flash = 1f;
                nextFlash = 5 + rnd.nextFloat() * 9;
            }
            if (flash > 0) {
                // duplo clarão: forte, apaga, mais fraco
                float a = flash > 0.7f ? (flash - 0.7f) / 0.3f : flash > 0.45f ? 0f : flash / 0.45f * 0.6f;
                c.drawColor(argb((int) (a * 150), 0xE8EEFF));
                flash -= dt * 2.2f;
            }
        }

        boolean animated = fx == Fx.RAIN || fx == Fx.DRIZZLE || fx == Fx.STORM || fx == Fx.SNOW;
        boolean slow = fx == Fx.SUN || fx == Fx.FOG || fx == Fx.CLOUDS || night;
        if (running && isShown() && (animated || slow)) {
            postInvalidateDelayed(animated ? 33 : 66);
        }
    }

    private void drawRain(Canvas c, int w, int h, float dt) {
        // céu carregado: mais escuro em cima
        shade.setShader(new LinearGradient(0, 0, 0, h, fx == Fx.STORM ? 0x80101826 : 0x5C101826,
                fx == Fx.STORM ? 0x40101826 : 0x26101826, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, shade);
        shade.setShader(null);
        streak.setStrokeWidth(fx == Fx.STORM ? 1.8f : 1.4f);
        float slant = 0.12f;
        for (int i = 0; i < sx.length; i++) {
            sy[i] += sSpeed[i] * dt;
            sx[i] += sSpeed[i] * slant * dt;
            if (sy[i] - sLen[i] > h) {
                sy[i] = -rnd.nextFloat() * 60;
                sx[i] = rnd.nextFloat() * (w + 100) - 100;
            }
            streak.setAlpha(sAlpha[i]);
            c.drawLine(sx[i], sy[i], sx[i] - sLen[i] * slant, sy[i] - sLen[i], streak);
        }
        // Gotas paradas no vidro que, de vez em quando, escorrem
        for (int i = 0; i < gx.length; i++) {
            gAge[i] += dt;
            float fadeIn = Math.min(1f, gAge[i] / 0.6f);
            if (gAge[i] > gLife[i]) {
                gSlide[i] += dt * (40 + gr[i] * 12);
                gy[i] += dt * (40 + gr[i] * 12);
                // rasto da gota a escorrer
                stroke.setStrokeWidth(gr[i] * 0.55f);
                stroke.setColor(argb((int) (40 * fadeIn), 0xDDE7F0));
                c.drawLine(gx[i], gy[i] - Math.min(gSlide[i], 70), gx[i], gy[i], stroke);
            }
            if (gy[i] - gr[i] > h) newDrop(i, w, h, false);
            float r = gr[i];
            fill.setShader(new RadialGradient(gx[i] - r * 0.3f, gy[i] - r * 0.3f, r * 1.2f,
                    new int[]{argb((int) (90 * fadeIn), 0xFFFFFF), argb((int) (30 * fadeIn), 0xB8C6D6),
                            argb((int) (55 * fadeIn), 0x1A2230)}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(gx[i], gy[i], r, fill);
            fill.setShader(null);
            stroke.setStrokeWidth(1f);
            stroke.setColor(argb((int) (60 * fadeIn), 0xFFFFFF));
            c.drawCircle(gx[i], gy[i], r, stroke);
        }
    }

    private void drawSnow(Canvas c, int w, int h, float dt) {
        c.drawColor(0x1AC8D2E0);
        for (int i = 0; i < fx_.length; i++) {
            fy[i] += fSpeed[i] * dt;
            float sway = (float) Math.sin(t * 0.9f + fPhase[i]) * (8 + fr[i] * 3);
            if (fy[i] - fr[i] > h) {
                fy[i] = -fr[i] - rnd.nextFloat() * 40;
                fx_[i] = rnd.nextFloat() * w;
            }
            fill.setColor(argb(150 + (int) (fr[i] * 20), 0xFFFFFF));
            c.drawCircle(fx_[i] + sway, fy[i], fr[i], fill);
        }
    }

    private void drawFog(Canvas c, int w, int h) {
        c.drawColor(0x33B8C0CA);
        for (int i = 0; i < 3; i++) {
            float y = h * (0.35f + i * 0.2f);
            float off = (float) Math.sin(t * 0.08f + i * 2.1f) * w * 0.25f;
            shade.setShader(new LinearGradient(off - w * 0.3f, 0, off + w * 1.3f, 0,
                    new int[]{0x00C8CDD3, 0x55C8CDD3, 0x00C8CDD3}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, y - h * 0.12f, w, y + h * 0.12f, shade);
        }
        shade.setShader(null);
    }

    private void drawSun(Canvas c, int w, int h) {
        float pulse = 0.85f + 0.15f * (float) Math.sin(t * 0.6f);
        float cx = w * 0.9f, cy = -h * 0.05f;
        fill.setShader(new RadialGradient(cx, cy, w * 0.55f,
                new int[]{argb((int) (170 * pulse), 0xFFF1C8), argb((int) (70 * pulse), 0xFFD89A), 0x00FFD89A},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, w * 0.55f, fill);
        fill.setShader(null);
        // reflexos da lente, em linha do sol para o centro
        float[] at = {0.35f, 0.55f, 0.72f};
        float[] size = {18, 34, 12};
        int[] col = {0xFFE7A8, 0xA8D8FF, 0xFFC8E0};
        for (int i = 0; i < at.length; i++) {
            float x = cx + (w * 0.42f - cx) * at[i];
            float y = cy + (h * 0.6f - cy) * at[i];
            fill.setColor(argb((int) (60 * pulse), col[i]));
            c.drawCircle(x, y, size[i], fill);
        }
    }

    private void drawClouds(Canvas c, int w, int h) {
        // sombras de nuvens a passar devagar
        for (int i = 0; i < 2; i++) {
            float x = ((t * 12 + i * w * 0.6f) % (w * 1.6f)) - w * 0.3f;
            fill.setShader(new RadialGradient(x, h * (0.3f + i * 0.25f), w * 0.35f,
                    new int[]{0x22000000, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawCircle(x, h * (0.3f + i * 0.25f), w * 0.35f, fill);
            fill.setShader(null);
        }
    }

    private static int argb(int a, int rgb) {
        return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
    }

    /** Código do tempo (Open-Meteo / WMO) → efeito. */
    public static Fx fromCode(int code) {
        if (code == 0 || code == 1) return Fx.SUN;
        if (code == 2 || code == 3) return Fx.CLOUDS;
        if (code == 45 || code == 48) return Fx.FOG;
        if (code >= 51 && code <= 57) return Fx.DRIZZLE;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Fx.SNOW;
        if (code >= 95) return Fx.STORM;
        if (code >= 61 && code <= 82) return Fx.RAIN;
        return Fx.NONE;
    }
}
