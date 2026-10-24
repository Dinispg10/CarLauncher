package pt.meucarro.launcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Animação de arranque: a foto do carro em ecrã inteiro com uma saudação,
 * a data e a temperatura. Dura uns 3,5 segundos; um toque salta-a.
 */
public final class Splash {

    private static final String TAG = "splash_overlay";
    private static final long HOLD_MS = 3200;

    private Splash() {
    }

    public static boolean isShowing(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        return content != null && content.findViewWithTag(TAG) != null;
    }

    public static void show(Activity activity, Bitmap photo) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || isShowing(activity)) return;

        ScaleFrame frame = new ScaleFrame(activity);
        frame.setTag(TAG);
        frame.setClickable(true); // não deixa tocar no ecrã de baixo durante a animação
        frame.setBackgroundColor(0xFF0B0D10);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xFF0B0D10);

        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (photo != null) image.setImageBitmap(photo);
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Escurece o canto de cima à esquerda, onde fica o texto, para se ler bem
        // mesmo por cima de um carro branco ou de um céu claro
        View shadeTop = new View(activity);
        shadeTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE60B0D10, 0x990B0D10, 0x000B0D10}));
        root.addView(shadeTop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360));
        View shadeLeft = new View(activity);
        shadeLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xB30B0D10, 0x590B0D10, 0x000B0D10}));
        root.addView(shadeLeft, new FrameLayout.LayoutParams(640, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.gravity = Gravity.TOP | Gravity.START;
        textLp.leftMargin = 60;
        textLp.topMargin = 48;
        root.addView(texts, textLp);

        TextView greeting = new TextView(activity);
        greeting.setText(greeting(activity));
        greeting.setTextColor(0xFFF2F3F5);
        greeting.setTypeface(Ui.font(activity, "300"));
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_PX, 118);
        greeting.setIncludeFontPadding(false);
        greeting.setMaxLines(1);
        greeting.setShadowLayer(24, 0, 2, 0xCC000000);
        greeting.setSingleLine(true); // nunca parte "Boa tarde" em duas linhas
        texts.addView(greeting, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(activity);
        sub.setText(subtitle(activity));
        sub.setTextColor(0xFFD5D8DC);
        sub.setTypeface(Ui.font(activity, "500"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28);
        sub.setShadowLayer(16, 0, 2, 0xCC000000);
        sub.setSingleLine(true);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = 12;
        texts.addView(sub, subLp);

        frame.addView(root);
        // Se o ecrã for estreito, encolhe o texto até caber numa linha
        root.post(() -> {
            fitWidth(greeting, root.getWidth() - 120, 48);
            fitWidth(sub, root.getWidth() - 120, 16);
        });
        content.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Animações: a foto aproxima-se devagar, o texto sobe e aparece
        image.setScaleX(1.12f);
        image.setScaleY(1.12f);
        image.setAlpha(0f);
        image.animate().alpha(1f).setDuration(700).start();
        image.animate().scaleX(1f).scaleY(1f).setDuration(HOLD_MS + 600)
                .setInterpolator(new DecelerateInterpolator()).start();
        texts.setAlpha(0f);
        texts.setTranslationY(-30f);
        texts.animate().alpha(1f).translationY(0f).setStartDelay(450).setDuration(900)
                .setInterpolator(new DecelerateInterpolator()).start();

        Runnable dismiss = () -> {
            if (frame.getParent() == null) return;
            frame.animate().alpha(0f).setDuration(600)
                    .withEndAction(() -> content.removeView(frame)).start();
        };
        frame.setOnClickListener(v -> dismiss.run());
        frame.postDelayed(dismiss, HOLD_MS);
    }

    private static void fitWidth(TextView view, int available, int minPx) {
        if (available <= 0) return;
        float size = view.getTextSize();
        android.text.TextPaint paint = new android.text.TextPaint(view.getPaint());
        String text = view.getText().toString();
        while (size > minPx) {
            paint.setTextSize(size);
            if (paint.measureText(text) <= available) break;
            size -= 2f;
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    private static String greeting(Activity activity) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = hour >= 5 && hour < 12 ? "Bom dia" : hour >= 12 && hour < 20 ? "Boa tarde" : "Boa noite";
        String name = driverName(activity);
        return name.isEmpty() ? g : g + ", " + name;
    }

    /** Nome na saudação (Definições → Ecrã inicial). */
    public static String driverName(Activity activity) {
        return activity.getSharedPreferences("launcher", Activity.MODE_PRIVATE)
                .getString("driver_name", "Dinis").trim();
    }

    private static String subtitle(Activity activity) {
        String date = new SimpleDateFormat("EEEE, d 'de' MMMM", Locale.forLanguageTag("pt-PT")).format(new Date());
        date = date.substring(0, 1).toUpperCase(Locale.ROOT) + date.substring(1);
        SharedPreferences p = activity.getSharedPreferences("launcher", Activity.MODE_PRIVATE);
        long age = System.currentTimeMillis() - p.getLong("w_time", 0);
        if (p.getBoolean("show_weather", true) && age < Weather.MAX_AGE_MS) {
            date += "  ·  " + Math.round(p.getFloat("w_temp", 0f)) + "°";
        }
        return date;
    }
}
