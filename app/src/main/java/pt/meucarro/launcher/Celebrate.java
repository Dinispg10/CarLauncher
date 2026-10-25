package pt.meucarro.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/** Pequena festa no ecrã: cartão a descer com o concelho novo e confetis a cair. Não bloqueia toques. */
public final class Celebrate {

    private Celebrate() {
    }

    public static void show(Activity a, String title, String subtitle, int accent) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return;
        ScaleFrame frame = new ScaleFrame(a);
        FrameLayout layer = new FrameLayout(a);
        frame.addView(layer);

        Confetti confetti = new Confetti(a, accent);
        layer.addView(confetti, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(40, 22, 40, 24);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(30f);
        bg.setColor(0xF213161B);
        bg.setStroke(2, accent);
        card.setBackground(bg);
        card.setElevation(20f);
        TextView t1 = new TextView(a);
        t1.setText("🎉  CONCELHO NOVO");
        t1.setTypeface(Ui.font(a, "700"));
        t1.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16);
        t1.setLetterSpacing(0.16f);
        t1.setTextColor(accent);
        card.addView(t1);
        TextView t2 = new TextView(a);
        t2.setText(title);
        t2.setTypeface(Ui.font(a, "600"));
        t2.setTextSize(TypedValue.COMPLEX_UNIT_PX, 48);
        t2.setTextColor(0xFFF2F3F5);
        t2.setSingleLine(true);
        card.addView(t2);
        TextView t3 = new TextView(a);
        t3.setText(subtitle);
        t3.setTypeface(Ui.font(a, "500"));
        t3.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20);
        t3.setTextColor(0xFFC9CDD3);
        card.addView(t3);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = 90;
        layer.addView(card, lp);

        content.addView(frame, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        card.setTranslationY(-260f);
        card.setAlpha(0f);
        card.animate().translationY(0f).alpha(1f).setDuration(650).setInterpolator(new OvershootInterpolator(1.2f))
                .start();
        frame.postDelayed(() -> card.animate().alpha(0f).translationY(-120f).setDuration(500).start(), 4200);
        frame.postDelayed(() -> content.removeView(frame), 4800);
    }

    /** Confetis coloridos a cair durante ~3,5 s. */
    private static final class Confetti extends View {
        private final float[] x = new float[140], y = new float[140], vy = new float[140], rot = new float[140],
                vr = new float[140], w = new float[140];
        private final int[] col = new int[140];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long start, last;

        Confetti(Context c, int accent) {
            super(c);
            Random r = new Random();
            int[] colors = {accent, 0xFFFBBF24, 0xFFF87171, 0xFF60A5FA, 0xFF4ADE80, 0xFFF2F3F5};
            for (int i = 0; i < x.length; i++) {
                x[i] = r.nextFloat() * 1100;
                y[i] = -r.nextFloat() * 600;
                vy[i] = 180 + r.nextFloat() * 260;
                rot[i] = r.nextFloat() * 360;
                vr[i] = (r.nextFloat() - 0.5f) * 540;
                w[i] = 6 + r.nextFloat() * 8;
                col[i] = colors[r.nextInt(colors.length)];
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            if (start == 0) {
                start = now;
                last = now;
            }
            float dt = (now - last) / 1000f;
            last = now;
            float age = (now - start) / 1000f;
            int alpha = age < 3f ? 255 : (int) Math.max(0, 255 * (1 - (age - 3f) / 0.8f));
            for (int i = 0; i < x.length; i++) {
                y[i] += vy[i] * dt;
                x[i] += (float) Math.sin(age * 3 + i) * 30 * dt;
                rot[i] += vr[i] * dt;
                p.setColor(col[i]);
                p.setAlpha(alpha);
                c.save();
                c.rotate(rot[i], x[i], y[i]);
                c.drawRect(x[i] - w[i] / 2, y[i] - w[i] / 4, x[i] + w[i] / 2, y[i] + w[i] / 4, p);
                c.restore();
            }
            if (alpha > 0) postInvalidateOnAnimation();
        }
    }
}
