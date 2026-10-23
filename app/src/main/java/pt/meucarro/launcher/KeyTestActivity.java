package pt.meucarro.launcher;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * Teste dos botões físicos: mostra cada tecla que chega à nossa app.
 * Botões que não aparecem aqui são tratados diretamente pelo sistema do rádio.
 */
public class KeyTestActivity extends Activity {

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private TextView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int accent = getSharedPreferences("launcher", MODE_PRIVATE).getInt("accent", 0xFF5EEAD4);

        ScaleFrame frame = new ScaleFrame(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0D10);
        root.setPadding(40, 24, 40, 24);
        frame.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Teste dos botões", "600", 30, 0xFFF2F3F5);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("Sair", "600", 19, 0xFF0B0D10);
        close.setGravity(Gravity.CENTER);
        close.setPadding(30, 0, 30, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(26f);
        bg.setColor(accent);
        close.setBackground(bg);
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 52));
        root.addView(top);

        TextView help = text("Carregue, um de cada vez, nos botões do rádio (NAVI, BAND, BACK, MENU, EQ, BT, ⏯, "
                + "rodar o TUNE) e nos do volante. Os que aparecem na lista podem ser usados pela nossa app. "
                + "Os que abrirem outra app ou não aparecerem são do sistema do rádio. "
                + "Para sair, toque em \"Sair\".", "500", 18, 0xFF9AA0A6);
        help.setMaxLines(6);
        help.setLineSpacing(4f, 1f);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        helpLp.topMargin = 12;
        root.addView(help, helpLp);

        list = text("Ainda nenhum botão.", "600", 22, 0xFFF2F3F5);
        list.setMaxLines(14);
        list.setLineSpacing(6f, 1f);
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(24f);
        card.setColor(0xFF13161B);
        list.setBackground(card);
        list.setPadding(26, 20, 26, 20);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = 16;
        root.addView(list, listLp);

        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            String line = KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", "")
                    + "   (código " + event.getKeyCode() + ", scan " + event.getScanCode() + ")";
            lines.addFirst(line);
            while (lines.size() > 12) lines.removeLast();
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            list.setText(sb.toString().trim());
            AudioLog.add("Botão: " + line);
        }
        return true; // fica com todas as teclas (incluindo BACK) para as poder ver
    }

    private TextView text(String s, String weight, int sizePx, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Ui.font(this, weight));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        return t;
    }
}
