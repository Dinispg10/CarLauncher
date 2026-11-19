package pt.meucarro.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** "Golf Wrapped": os ecrãs do resumo do mês/ano (página em assets/wrapped, com os dados deste rádio). */
public class WrappedActivity extends Activity {

    public static void open(Context c, String key) {
        c.startActivity(new Intent(c, WrappedActivity.class).putExtra("key", key));
    }

    private static String concJson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String key = getIntent().getStringExtra("key");
        if (key == null) key = Wrapped.monthKey(System.currentTimeMillis());
        JSONObject data = Wrapped.summary(this, key);
        if (data == null) {
            Toast.makeText(this, "Ainda não há dados para " + Wrapped.title(key), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!data.optBoolean("partial")) Wrapped.markSeen(this, key);
        CloudSync.wrapped(this, key, data);
        WebView web;
        try {
            web = new WebView(this);
        } catch (Exception e) {
            Toast.makeText(this, "Este rádio não consegue mostrar o resumo (falta o WebView)", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        web.setBackgroundColor(Color.parseColor("#0B0D10"));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Golf");
        setContentView(web);
        try {
            String html = asset("wrapped/index.html");
            html = html.replace("<script>\nvar D = window.GOLF_DATA",
                    "<script>window.GOLF_DATA=" + data.toString().replace("</", "<\\/") + ";</script>\n<script>\nvar D = window.GOLF_DATA");
            web.loadDataWithBaseURL("file:///android_asset/wrapped/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
    }

    private String asset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Contornos dos concelhos para os mapas (simplificados: um ponto em cada três). */
    private String concelhos() {
        if (concJson != null) return concJson;
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Concelhos.Concelho k : Concelhos.all(this)) {
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(JSONObject.quote(k.name));
            for (int[] r : k.rings) {
                sb.append(",[");
                int n = r.length / 2, step = n > 30 ? 3 : 1;
                boolean f2 = true;
                for (int i = 0; i < n; i += step) {
                    if (!f2) sb.append(',');
                    f2 = false;
                    sb.append(r[i * 2]).append(',').append(r[i * 2 + 1]);
                }
                sb.append(']');
            }
            sb.append(']');
        }
        concJson = sb.append(']').toString();
        return concJson;
    }

    private class Bridge {
        @JavascriptInterface
        public String concelhos() {
            return WrappedActivity.this.concelhos();
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(WrappedActivity.this::finish);
        }
    }

    /** Lista para escolher que resumo ver (meses e anos com dados). */
    public static void chooser(Activity a) {
        java.util.List<String> months = Wrapped.months(a);
        Sheet sheet = Sheet.create(a, "✨ Golf Wrapped");
        if (months.isEmpty()) {
            sheet.message("Ainda não há dados. A partir de agora, cada viagem conta para o resumo do mês.");
        } else {
            sheet.message("O resumo de cada mês aparece sozinho no dia 1. Aqui podes ver qualquer um.");
            java.util.Set<String> years = new java.util.LinkedHashSet<>();
            String now = Wrapped.monthKey(System.currentTimeMillis());
            for (String m : months) {
                years.add(m.substring(0, 4));
                String label = Wrapped.title(m) + (m.equals(now) ? " (até agora)" : "");
                sheet.item(label, () -> open(a, m));
            }
            for (String y : years) {
                boolean thisYear = y.equals(now.substring(0, 4));
                sheet.item("O ano " + y + (thisYear ? " (até agora)" : ""), () -> open(a, y));
            }
        }
        sheet.button("Fechar", false, null).show();
    }

    /** No início do mês: oferece o resumo do mês (ou do ano) que acabou. Só com o carro parado. */
    public static void offerIfDue(Activity a) {
        if (TripService.speedKmh() > 5) return;
        String key = Wrapped.pending(a);
        if (key == null) return;
        android.content.SharedPreferences p = a.getSharedPreferences("launcher", MODE_PRIVATE);
        String today = Wrapped.monthKey(System.currentTimeMillis()) + "-" + java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH);
        if (today.equals(p.getString("wrap_offered", ""))) return; // uma vez por dia
        p.edit().putString("wrap_offered", today).apply();
        String what = key.length() == 4 ? "O teu " + key : "O teu " + Wrapped.title(key).split(" ")[0];
        Sheet.create(a, "✨ " + what + " está pronto")
                .message("O Golf Wrapped: km, viagens, consumos, concelhos novos e a hora a que mais conduzes.")
                .button("Depois", false, null)
                .button("Ver agora", true, () -> open(a, key))
                .show();
    }
}
