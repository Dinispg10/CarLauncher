package pt.meucarro.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Músicas offline": os álbuns guardados no rádio, para quando o telemóvel não está ligado.
 * Grelha de álbuns → álbum (capa + lista de músicas). Tocar abre o ecrã "A tocar" de sempre.
 */
public class OfflineActivity extends Activity {

    private static final int TEXT = 0xFFF2F3F5, SOFT = 0xFFC9CDD3, MUTED = 0xFF9AA0A6, CARD = 0xFF13161B, LINE = 0xFF22262D;
    private static final ExecutorService ART = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private int accent;
    private FrameLayout body;
    private TextView title;
    private LinearLayout chips;
    private OfflineMusic.Album openAlbum;
    private final Runnable onPlayer = this::refreshPlaying;
    private LinearLayout trackList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accent = Ui.effectiveAccent(this, MediaHub.get(this));
        ScaleFrame frame = new ScaleFrame(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 20, 40, 24);
        root.setBackgroundColor(0xFF0B0D10);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // Barra de cima
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(pill("‹  Início", v -> {
            if (openAlbum != null) showAlbums();
            else finish();
        }), new LinearLayout.LayoutParams(-2, 52));
        title = text("Músicas offline", 30, TEXT, "600");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.setMarginStart(22);
        top.addView(title, tp);
        chips = new LinearLayout(this);
        top.addView(chips, new LinearLayout.LayoutParams(-2, 48));
        root.addView(top, new LinearLayout.LayoutParams(-1, 56));

        body = new FrameLayout(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1);
        bp.topMargin = 18;
        root.addView(body, bp);

        setContentView(frame);
        Ui.addClock(this);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
        OfflineService.addListener(onPlayer);
    }

    @Override
    protected void onPause() {
        OfflineService.removeListener(onPlayer);
        super.onPause();
    }

    private void load() {
        body.removeAllViews();
        TextView wait = text("A ler as músicas…", 22, MUTED, "500");
        wait.setGravity(Gravity.CENTER);
        body.addView(wait, new FrameLayout.LayoutParams(-1, -1));
        new Thread(() -> {
            List<OfflineMusic.Album> albums = OfflineMusic.albums(this);
            OfflineMusic.prefetchLyrics(this); // guarda as letras enquanto há internet
            main.post(() -> {
                if (isFinishing()) return;
                if (openAlbum != null) {
                    for (OfflineMusic.Album a : albums) if (a.key.equals(openAlbum.key)) {
                        showAlbum(a);
                        return;
                    }
                }
                showAlbums();
            });
        }).start();
    }

    // ---------- Grelha de álbuns ----------

    private void showAlbums() {
        openAlbum = null;
        trackList = null;
        title.setText("Músicas offline");
        List<OfflineMusic.Album> albums = OfflineMusic.albums(this);
        chips.removeAllViews();
        if (!albums.isEmpty()) {
            if (OfflineService.hasResume(this)) chip("▶  Continuar", true, v -> {
                OfflineService.resume(this);
                openNowPlaying();
            });
            chip("🔀  Aleatório", false, v -> {
                OfflineService.play(this, OfflineMusic.allTracks(this), (int) (Math.random() * OfflineMusic.allTracks(this).size()), true);
                openNowPlaying();
            });
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            chip("Pen USB", false, v -> requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 7));
        }
        chip("＋  Adicionar", false, v -> showHowToAdd());
        Ui.style(chips, accent);

        body.removeAllViews();
        if (albums.isEmpty()) {
            body.addView(emptyState(), new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid);
        int perRow = 5, tile = 172;
        LinearLayout row = null;
        for (int i = 0; i < albums.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = 22;
                grid.addView(row, rp);
            }
            OfflineMusic.Album a = albums.get(i);
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            ImageView cover = coverView(a, tile, 20);
            t.addView(cover, new LinearLayout.LayoutParams(tile, tile));
            TextView n = text(a.name, 18, TEXT, "600");
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(tile, -2);
            np.topMargin = 10;
            t.addView(n, np);
            TextView ar = text(a.artist == null ? a.tracks.size() + " músicas" : a.artist, 15, MUTED, "500");
            ar.setSingleLine(true);
            ar.setEllipsize(TextUtils.TruncateAt.END);
            t.addView(ar, new LinearLayout.LayoutParams(tile, -2));
            t.setOnClickListener(v -> showAlbum(a));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, -2);
            lp.setMarginEnd(i % perRow == perRow - 1 ? 0 : (944 - tile * perRow) / (perRow - 1));
            row.addView(t, lp);
        }
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    // ---------- Um álbum ----------

    private void showAlbum(OfflineMusic.Album a) {
        openAlbum = a;
        title.setText("");
        chips.removeAllViews();
        body.removeAllViews();
        LinearLayout row = new LinearLayout(this);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(coverView(a, 280, 26), new LinearLayout.LayoutParams(280, 280));
        TextView n = text(a.name, 28, TEXT, "600");
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(280, -2);
        np.topMargin = 14;
        left.addView(n, np);
        long min = Math.round(a.durationMs() / 60000.0);
        left.addView(text((a.artist != null ? a.artist + "\n" : "") + a.tracks.size() + " músicas" + (min > 0 ? " · " + min + " min" : ""),
                16, MUTED, "500"), new LinearLayout.LayoutParams(280, -2));
        LinearLayout btns = new LinearLayout(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, 52);
        bp.topMargin = 16;
        TextView playAll = button("▶  Tocar", true);
        playAll.setOnClickListener(v -> {
            OfflineService.play(this, a.tracks, 0, false);
            openNowPlaying();
        });
        btns.addView(playAll, new LinearLayout.LayoutParams(-2, 52));
        TextView shuf = button("🔀", false);
        shuf.setOnClickListener(v -> {
            OfflineService.play(this, a.tracks, (int) (Math.random() * a.tracks.size()), true);
            openNowPlaying();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, 52);
        sp.setMarginStart(10);
        btns.addView(shuf, sp);
        TextView del = button("🗑", false);
        del.setOnClickListener(v -> Sheet.confirm(this, "Apagar \"" + a.name + "\"?",
                "As músicas deste álbum são apagadas do rádio. (Numa pen USB não mexe.)", "Apagar", () -> {
                    int k = OfflineMusic.deleteAlbum(this, a.key);
                    android.widget.Toast.makeText(this, k + " músicas apagadas", android.widget.Toast.LENGTH_SHORT).show();
                    openAlbum = null;
                    load();
                }));
        btns.addView(del, sp);
        left.addView(btns, bp);
        row.addView(left, new LinearLayout.LayoutParams(280, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        trackList = new LinearLayout(this);
        trackList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(trackList);
        for (int i = 0; i < a.tracks.size(); i++) {
            OfflineMusic.Track t = a.tracks.get(i);
            final int idx = i;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(18, 0, 18, 0);
            r.setTag(t.path);
            TextView num = text(String.valueOf(t.number > 0 ? t.number : i + 1), 18, MUTED, "600");
            num.setGravity(Gravity.CENTER);
            r.addView(num, new LinearLayout.LayoutParams(44, -2));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            TextView tt = text(t.title, 21, TEXT, "600");
            tt.setSingleLine(true);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(tt);
            if (t.artist != null && a.artist != null && !t.artist.equals(a.artist)) {
                TextView ta = text(t.artist, 15, MUTED, "500");
                ta.setSingleLine(true);
                mid.addView(ta);
            }
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.setMarginStart(10);
            r.addView(mid, mp);
            r.addView(text(time(t.durationMs), 17, MUTED, "500"));
            r.setOnClickListener(v -> {
                OfflineService.play(this, a.tracks, idx, false);
                openNowPlaying();
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 68);
            rp.bottomMargin = 6;
            trackList.addView(r, rp);
        }
        FrameLayout card = new FrameLayout(this);
        card.setBackground(round(CARD, 24, LINE));
        card.setPadding(10, 10, 10, 10);
        card.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1);
        cp.setMarginStart(34);
        row.addView(card, cp);
        body.addView(row, new FrameLayout.LayoutParams(-1, -1));
        refreshPlaying();
    }

    /** Destaca a música que está a tocar. */
    private void refreshPlaying() {
        if (trackList == null) return;
        String cur = OfflineService.currentPath();
        for (int i = 0; i < trackList.getChildCount(); i++) {
            View r = trackList.getChildAt(i);
            boolean on = cur != null && cur.equals(r.getTag());
            r.setBackground(on ? round((accent & 0x00FFFFFF) | 0x26000000, 16, 0) : null);
            TextView num = (TextView) ((LinearLayout) r).getChildAt(0);
            num.setTextColor(on ? accent : MUTED);
            if (on) num.setText("♪");
        }
    }

    private void openNowPlaying() {
        main.postDelayed(() -> startActivity(new android.content.Intent(this, NowPlayingActivity.class)), 350);
    }

    // ---------- Sem músicas / como adicionar ----------

    private View emptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView icon = text("♪", 64, 0xFF2C313A, "600");
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);
        TextView t = text("Ainda não há músicas no rádio", 30, TEXT, "600");
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        TextView s = text("Envia MP3 a partir do telemóvel (ou do PC) pela página do rádio,\nou liga uma pen USB com músicas.", 19, MUTED, "500");
        s.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = 10;
        box.addView(s, sp);
        TextView b = button("＋  Adicionar músicas", true);
        b.setOnClickListener(v -> showHowToAdd());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, 56);
        bp.topMargin = 26;
        box.addView(b, bp);
        return box;
    }

    private void showHowToAdd() {
        ApkShare.onReceived = what -> {
            if ("music".equals(what)) load();
        };
        String url = ApkShare.start(this);
        if (url == null) {
            Sheet.message(this, "Adicionar músicas",
                    "O rádio não está ligado a nenhuma rede Wi-Fi.\n\nLiga o hotspot do telemóvel, liga o rádio a esse Wi-Fi e tenta outra vez.");
            return;
        }
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(CloudSync.qrFor(url + "/#musica", 6));
        v.addView(qr, new LinearLayout.LayoutParams(220, 220));
        TextView t = text("1) O telemóvel com o hotspot ligado e o rádio ligado a ele.\n\n"
                + "2) Aponta a câmara do iPhone a este código, ou escreve no Safari:\n      " + url + "\n\n"
                + "3) Em \"Músicas offline\", escolhe os MP3 (podes escolher vários de uma vez, "
                + "na app Ficheiros) e carrega em Enviar.\n\nOs álbuns aparecem aqui sozinhos.", 19, SOFT, "500");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.setMarginStart(26);
        v.addView(t, tp);
        Sheet.create(this, "Adicionar músicas").width(900).view(v)
                .button("OK", true, null).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        OfflineMusic.invalidate();
        load();
    }

    // ---------- Peças ----------

    private ImageView coverView(OfflineMusic.Album a, int size, int radius) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(round(0xFF1A1E24, radius, 0));
        iv.setClipToOutline(true);
        iv.setImageResource(R.drawable.ic_music);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setColorFilter(0xFF3A404A);
        String path = a.tracks.get(0).path;
        ART.execute(() -> {
            Bitmap b = OfflineMusic.art(this, path, size >= 240 ? 600 : 240);
            if (b != null) main.post(() -> {
                iv.clearColorFilter();
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(b);
            });
        });
        return iv;
    }

    private TextView text(String s, int px, int color, String weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setTypeface(Ui.font(this, weight));
        t.setIncludeFontPadding(false);
        t.setLineSpacing(4, 1f);
        return t;
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView t = text(s, 18, TEXT, "600");
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(18, 0, 22, 0);
        t.setBackgroundResource(R.drawable.pill_bg);
        t.setOnClickListener(l);
        return t;
    }

    private void chip(String s, boolean primary, View.OnClickListener l) {
        TextView t = button(s, primary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, 48);
        lp.setMarginStart(12);
        chips.addView(t, lp);
    }

    private TextView button(String s, boolean primary) {
        TextView t = text(s, 19, primary ? 0xFF0B0D10 : SOFT, "600");
        t.setGravity(Gravity.CENTER);
        t.setPadding(22, 0, 22, 0);
        if (primary) t.setBackground(round(accent, 26, 0));
        else t.setBackgroundResource(R.drawable.chip_bg);
        return t;
    }

    private static GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(1, stroke);
        return d;
    }

    private static String time(long ms) {
        if (ms <= 0) return "";
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
