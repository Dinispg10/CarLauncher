package pt.meucarro.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Janela com o design do launcher (em vez das janelas de origem do Android):
 * fundo escuro, cantos redondos, letra Rajdhani, linhas grandes para tocar a conduzir.
 *
 * Uso: Sheet.create(activity, "Título").section("…").item("…", valor, fecha, ação).button(…).show();
 */
public final class Sheet {

    private static final class Entry {
        String section;
        String label;
        Supplier<String> value;
        Drawable icon;
        boolean closes;
        Runnable action;
    }

    private static final class Button {
        String label;
        boolean primary;
        Runnable action;
    }

    /** ScrollView com altura máxima: cresce com o conteúdo até ao limite e depois desliza. */
    private static final class MaxScroll extends ScrollView {
        private final int max;

        MaxScroll(Context c, int max) {
            super(c);
            this.max = max;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    private final Activity activity;
    private final String title;
    private final int accent;
    private String message;
    private int width = 600;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private Dialog dialog;
    private LinearLayout rows;
    private String inputInitial;
    private View custom;
    private android.widget.EditText input;

    private Sheet(Activity activity, String title) {
        this.activity = activity;
        this.title = title;
        this.accent = Ui.effectiveAccent(activity, MediaHub.get(activity));
    }

    public static Sheet create(Activity activity, String title) {
        return new Sheet(activity, title);
    }

    // ---------- Construção ----------

    public Sheet message(String text) {
        this.message = text;
        return this;
    }

    /** Caixa para escrever (ex.: um nome). O texto lê-se com inputValue() na ação do botão. */
    public Sheet input(String initial) {
        this.inputInitial = initial == null ? "" : initial;
        return this;
    }

    public String inputValue() {
        return input == null ? "" : input.getText().toString().trim();
    }

    /** Uma vista própria por baixo da mensagem (ex.: um código QR). */
    public Sheet view(View v) {
        this.custom = v;
        return this;
    }

    public Sheet width(int px) {
        this.width = px;
        return this;
    }

    public Sheet section(String name) {
        Entry e = new Entry();
        e.section = name;
        entries.add(e);
        return this;
    }

    /** Linha com valor à direita (ex.: "Ligado"). closes=false deixa a janela aberta e atualiza os valores. */
    public Sheet item(String label, Supplier<String> value, boolean closes, Runnable action) {
        Entry e = new Entry();
        e.label = label;
        e.value = value;
        e.closes = closes;
        e.action = action;
        entries.add(e);
        return this;
    }

    public Sheet item(String label, Runnable action) {
        return item(label, null, true, action);
    }

    public Sheet item(Drawable icon, String label, Runnable action) {
        item(label, null, true, action);
        entries.get(entries.size() - 1).icon = icon;
        return this;
    }

    public Sheet button(String label, boolean primary, Runnable action) {
        Button b = new Button();
        b.label = label;
        b.primary = primary;
        b.action = action;
        buttons.add(b);
        return this;
    }

    // ---------- Atalhos ----------

    public static void message(Activity a, String title, String text) {
        create(a, title).message(text).button("OK", true, null).show();
    }

    public static void confirm(Activity a, String title, String text, String ok, Runnable onOk) {
        create(a, title).message(text).button("Cancelar", false, null).button(ok, true, onOk).show();
    }

    // ---------- Mostrar ----------

    public void show() {
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);

        ScaleFrame frame = new ScaleFrame(activity);
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setOnClickListener(v -> dialog.dismiss());
        frame.addView(scrim);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true); // tocar no cartão não fecha a janela
        card.setPadding(26, 24, 26, 22);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF13161B);
        bg.setCornerRadius(28f);
        bg.setStroke(1, 0xFF2C313A);
        card.setBackground(bg);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        scrim.addView(card, cardLp);

        TextView titleView = text(title, "600", 28, 0xFFF2F3F5);
        titleView.setPadding(16, 0, 16, 12);
        card.addView(titleView);

        int maxBody = 600 - 60 - 70 - (buttons.isEmpty() ? 0 : 76);
        MaxScroll scroll = new MaxScroll(activity, maxBody);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFadingEdgeLength(24);
        scroll.setVerticalFadingEdgeEnabled(true);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        card.addView(scroll);

        if (message != null) {
            TextView msg = text(message, "500", 20, 0xFFC9CDD3);
            msg.setMaxLines(Integer.MAX_VALUE);
            msg.setLineSpacing(4f, 1f);
            msg.setPadding(16, 0, 16, entries.isEmpty() ? 4 : 12);
            body.addView(msg);
        }
        if (custom != null) body.addView(custom);
        if (inputInitial != null) {
            input = new android.widget.EditText(activity);
            input.setText(inputInitial);
            input.setSelection(input.getText().length());
            input.setSingleLine(true);
            input.setTypeface(Ui.font(activity, "600"));
            input.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
            input.setTextColor(0xFFF2F3F5);
            input.setHintTextColor(0xFF6B7280);
            input.setPadding(20, 12, 20, 12);
            GradientDrawable ib = new GradientDrawable();
            ib.setColor(0xFF0B0D10);
            ib.setCornerRadius(18f);
            ib.setStroke(2, accent);
            input.setBackground(ib);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64);
            ilp.setMargins(16, 4, 16, 8);
            body.addView(input, ilp);
        }
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(rows);
        renderRows();

        if (!buttons.isEmpty()) {
            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.END);
            bar.setPadding(0, 16, 0, 0);
            for (Button b : buttons) bar.addView(buttonView(b));
            card.addView(bar);
        }

        dialog.setContentView(frame);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            hideBars(window);
        }

        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        dialog.show();
        if (window != null) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        if (window != null && input != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
            input.requestFocus();
        }
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
    }

    public void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    /** Volta a desenhar as linhas (para os valores à direita mostrarem o estado novo). */
    public void refresh() {
        if (rows != null) renderRows();
    }

    private void renderRows() {
        rows.removeAllViews();
        boolean first = true;
        for (Entry e : entries) {
            if (e.section != null) {
                TextView s = text(e.section.toUpperCase(java.util.Locale.ROOT), "700", 13, accent);
                s.setLetterSpacing(0.14f);
                s.setPadding(16, first ? 4 : 18, 16, 6);
                rows.addView(s);
            } else {
                rows.addView(rowView(e));
            }
            first = false;
        }
    }

    private View rowView(Entry e) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16, 0, 16, 0);
        row.setMinimumHeight(60);
        row.setClickable(true);
        row.setFocusable(true);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(16f);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), null, mask));

        if (e.icon != null) {
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(e.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(42, 42);
            lp.rightMargin = 16;
            row.addView(icon, lp);
        }
        TextView label = text(e.label, "500", 21, 0xFFF2F3F5);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (e.value != null) {
            String v = e.value.get();
            if (v != null) {
                TextView value = text(v, "600", 18, accent);
                value.setPadding(16, 0, 0, 0);
                row.addView(value);
            }
        }
        row.setOnClickListener(v -> {
            if (e.closes) dismiss();
            if (e.action != null) e.action.run();
            if (!e.closes) refresh();
        });
        return row;
    }

    private View buttonView(Button b) {
        TextView t = text(b.label, "600", 19, b.primary ? 0xFF0B0D10 : 0xFFF2F3F5);
        t.setGravity(Gravity.CENTER);
        t.setPadding(28, 0, 28, 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(26f);
        shape.setColor(b.primary ? accent : 0xFF1E2228);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        t.setClickable(true);
        t.setOnClickListener(v -> {
            dismiss();
            if (b.action != null) b.action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 52);
        lp.leftMargin = 12;
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String s, String weight, int sizePx, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTypeface(Ui.font(activity, weight));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        return t;
    }

    /** Mantém a barra do sistema escondida também enquanto a janela está aberta. */
    @SuppressWarnings("deprecation")
    private void hideBars(Window window) {
        if (activity.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("show_bars", false)) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
