package pt.meucarro.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Listar, detetar e abrir as apps instaladas no rádio. */
public final class AppUtils {

    private AppUtils() {
    }

    public static final class AppEntry {
        public final String label;
        public final String pkg;
        public final String cls;
        public final Drawable icon;

        AppEntry(String label, String pkg, String cls, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.cls = cls;
            this.icon = icon;
        }
    }

    /** Todas as apps com ícone no menu, por ordem alfabética (sem este launcher). */
    public static List<AppEntry> listApps(Context context, boolean withIcons) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);
        List<AppEntry> apps = new ArrayList<>();
        for (ResolveInfo info : infos) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(context.getPackageName())) continue;
            String label = String.valueOf(info.loadLabel(pm));
            Drawable icon = withIcons ? info.loadIcon(pm) : null;
            apps.add(new AppEntry(label, pkg, info.activityInfo.name, icon));
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    /**
     * Procura a primeira app cujo nome ou pacote contenha uma das palavras,
     * pela ordem das palavras. Palavras curtas (2 letras) têm de ser o nome exato.
     */
    public static AppEntry detect(List<AppEntry> apps, String[] keywords) {
        for (String keyword : keywords) {
            for (AppEntry app : apps) {
                String label = app.label.toLowerCase(Locale.ROOT).trim();
                String pkg = app.pkg.toLowerCase(Locale.ROOT);
                if (keyword.length() <= 2) {
                    if (label.equals(keyword)) return app;
                } else if (label.contains(keyword) || pkg.contains(keyword.replace(" ", ""))) {
                    return app;
                }
            }
        }
        return null;
    }

    public static boolean launch(Context context, String pkg, String cls) {
        if (pkg == null) return false;
        try {
            Intent intent;
            if (cls != null) {
                intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setComponent(new ComponentName(pkg, cls));
            } else {
                intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent == null) return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            // Tenta só pelo pacote, caso a atividade tenha mudado de nome numa atualização
            if (cls != null) return launch(context, pkg, null);
            return false;
        }
    }
}
