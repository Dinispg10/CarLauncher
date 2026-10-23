package pt.meucarro.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Gaveta com todas as apps instaladas. Toque longo numa app abre as informações dela. */
public class AppsActivity extends Activity {

    private ArrayAdapter<AppUtils.AppEntry> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        View root = findViewById(android.R.id.content);
        int accent = getSharedPreferences("launcher", MODE_PRIVATE).getInt("accent", 0xFF5EEAD4);
        Ui.style(root, accent);
        Ui.applySystemBars(this);
        Night.apply(this, Night.isNight(this));

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_settings).setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        adapter = new ArrayAdapter<AppUtils.AppEntry>(this, 0, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View cell = convertView != null ? convertView
                        : LayoutInflater.from(getContext()).inflate(R.layout.item_app_grid, parent, false);
                AppUtils.AppEntry app = getItem(position);
                ((ImageView) cell.findViewById(R.id.icon)).setImageDrawable(app.icon);
                TextView label = cell.findViewById(R.id.label);
                label.setText(app.label);
                label.setTypeface(Ui.font(getContext(), "500"));
                return cell;
            }
        };

        GridView grid = findViewById(R.id.grid);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            AppUtils.AppEntry app = adapter.getItem(position);
            if (app != null && AppUtils.launch(this, app.pkg, app.cls)) {
                finish();
            } else {
                Toast.makeText(this, "Não foi possível abrir a app", Toast.LENGTH_SHORT).show();
            }
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            AppUtils.AppEntry app = adapter.getItem(position);
            if (app == null) return false;
            Sheet.create(this, app.label)
                    .item("Esconder desta lista", () -> hide(app))
                    .item("Informações da app", () -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + app.pkg)));
                        } catch (Exception ignored) {
                        }
                    })
                    .button("Cancelar", false, null)
                    .show();
            return true;
        });

        // Carregar a lista fora do ecrã principal: em rádios lentos pode demorar um pouco.
        new Thread(() -> {
            List<AppUtils.AppEntry> apps = AppUtils.listApps(this, true);
            java.util.Set<String> hidden = getSharedPreferences("launcher", MODE_PRIVATE)
                    .getStringSet("hidden_apps", new java.util.HashSet<>());
            apps.removeIf(a -> hidden.contains(a.pkg + "/" + a.cls));
            runOnUiThread(() -> {
                adapter.clear();
                adapter.addAll(apps);
            });
        }).start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.applySystemBars(this);
    }

    /** Tira a app da lista (não a desinstala). Volta a aparecer em Definições → Mostrar apps escondidas. */
    private void hide(AppUtils.AppEntry app) {
        android.content.SharedPreferences prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        java.util.Set<String> hidden = new java.util.HashSet<>(
                prefs.getStringSet("hidden_apps", new java.util.HashSet<>()));
        hidden.add(app.pkg + "/" + app.cls);
        prefs.edit().putStringSet("hidden_apps", hidden).apply();
        adapter.remove(app);
        Toast.makeText(this, app.label + " escondida. Para voltar a mostrar: Definições do launcher.",
                Toast.LENGTH_LONG).show();
    }
}
