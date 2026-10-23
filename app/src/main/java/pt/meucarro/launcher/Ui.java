package pt.meucarro.launcher;

import android.app.Activity;
import android.content.Context;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Aplica a letra Rajdhani e a cor de destaque aos elementos marcados com
 * android:tag nos layouts. Exemplos de tags: "w200", "w600 accent_text",
 * "accent_bg" (botão redondo) e "accent_bg_sq" (botão quadrado arredondado).
 */
public final class Ui {

    private static final Map<String, Typeface> FONTS = new HashMap<>();

    private Ui() {
    }

    public static Typeface font(Context context, String weight) {
        Typeface cached = FONTS.get(weight);
        if (cached != null) return cached;
        Typeface tf;
        try {
            // Rajdhani não tem peso 200: usa o 300, o mais fino que existe
            String w = weight.equals("200") ? "300" : weight;
            tf = Typeface.createFromAsset(context.getAssets(), "fonts/rajdhani-" + w + ".ttf");
        } catch (Exception e) {
            tf = Typeface.create("sans-serif", Typeface.NORMAL);
        }
        FONTS.put(weight, tf);
        return tf;
    }

    /**
     * Esconde a barra do sistema (casa, relógio, ícones) nos ecrãs do launcher,
     * a não ser que o utilizador a tenha mandado mostrar nas definições.
     * Deslizar do topo para baixo mostra-a por uns segundos.
     */
    @SuppressWarnings("deprecation")
    public static void applySystemBars(Activity activity) {
        boolean show = activity.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                .getBoolean("show_bars", false);
        Window window = activity.getWindow();
        View decor = window.getDecorView();
        if (show) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** Aplica letra e cor de destaque a toda a árvore de vistas. */
    public static void style(View view, int accent) {
        walk(view, accent, true);
    }

    /** Muda só a cor de destaque (mais leve; usado quando a música muda). */
    public static void accent(View view, int accent) {
        walk(view, accent, false);
    }

    /** Cor de destaque a usar: a da capa (se ligado e houver) ou a escolhida nas definições. */
    public static int effectiveAccent(Context context, MediaHub hub) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        int chosen = prefs.getInt("accent", 0xFF5EEAD4);
        if (prefs.getBoolean("dynamic_color", true) && hub != null && hub.currentArtColor() != null) {
            return hub.currentArtColor();
        }
        return chosen;
    }

    /** Passa suavemente de uma cor de destaque para outra (≈ meio segundo). */
    public static void animateAccent(View root, int from, int to) {
        if (from == to) return;
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        animator.setDuration(500);
        animator.addUpdateListener(a -> accent(root, (Integer) a.getAnimatedValue()));
        animator.start();
    }

    private static void walk(View view, int accent, boolean fonts) {
        Object tagObj = view.getTag();
        if (tagObj instanceof String) {
            String[] tokens = ((String) tagObj).split(" ");
            for (String token : tokens) {
                applyToken(view, token, accent, fonts);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), accent, fonts);
            }
        }
    }

    private static void applyToken(View view, String token, int accent, boolean fonts) {
        if (token.length() == 4 && token.charAt(0) == 'w' && view instanceof TextView) {
            if (fonts) ((TextView) view).setTypeface(font(view.getContext(), token.substring(1)));
        } else if (token.equals("accent_tint") && view instanceof android.widget.ImageView) {
            ((android.widget.ImageView) view).setColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN);
        } else if (token.equals("accent_text") && view instanceof TextView) {
            ((TextView) view).setTextColor(accent);
        } else if (token.equals("accent_bg")) {
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(accent);
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), shape, null));
        } else if (token.equals("accent_bar")) {
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius(3f);
            shape.setColor(accent);
            view.setBackground(shape);
        } else if (token.equals("accent_bg_sq")) {
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius(18f);
            shape.setColor(accent);
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), shape, null));
        }
    }
}
