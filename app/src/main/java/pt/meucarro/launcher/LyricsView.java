package pt.meucarro.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Letra da música a deslizar.
 * Com tempos: a linha que está a ser cantada fica grande e branca, ao meio.
 * Sem tempos: a letra toda desce devagar ao ritmo da música (a meio da música, mais ou menos a meio
 * da letra), sem linha destacada. Em ambos dá para arrastar com o dedo; passados uns segundos volta sozinha.
 */
public class LyricsView extends View {

    private static final int PAD_X = 36;
    private static final int GAP = 16;
    private static final int CARD = 0xFF13161B;

    private final TextPaint normal = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint msgTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();

    private List<Lyrics.Line> lines = new ArrayList<>();
    private boolean synced;
    private StaticLayout[] normalL, bigL;
    private int builtWidth = -1;

    private String message, messageSub;
    private StaticLayout msgL, msgSubL;

    private String footer = "";

    // Posição da música (âncora + tempo em que foi lida, para andar entre atualizações)
    private long anchorPos, anchorAt, durationMs;
    private boolean playing;

    private float scroll = Float.NaN;
    private long lastFrame;
    private long manualUntil;
    private float downY, downScroll;
    private boolean dragging;

    public LyricsView(Context c) {
        this(c, null);
    }

    public LyricsView(Context c, AttributeSet a) {
        super(c, a);
        normal.setTypeface(Ui.font(c, "600"));
        normal.setTextSize(30);
        big.setTypeface(Ui.font(c, "700"));
        big.setTextSize(38);
        small.setTypeface(Ui.font(c, "500"));
        small.setTextSize(14);
        small.setColor(0xFF5D636C);
        small.setTextAlign(Paint.Align.RIGHT);
        msgTitle.setTypeface(Ui.font(c, "600"));
        msgTitle.setTextSize(28);
        msgTitle.setColor(0xFFF2F3F5);
        note.setTypeface(Ui.font(c, "500"));
        note.setTextSize(19);
        note.setColor(0xFF9AA0A6);
    }

    /** Mostra uma mensagem em vez da letra (a procurar, sem letra, ...). */
    public void setMessage(String title, String sub) {
        message = title;
        messageSub = sub;
        msgL = msgSubL = null;
        lines = new ArrayList<>();
        normalL = bigL = null;
        footer = "";
        invalidate();
    }

    public void setLyrics(List<Lyrics.Line> lines, boolean synced, String source) {
        this.lines = lines;
        this.synced = synced;
        message = null;
        builtWidth = -1;
        scroll = Float.NaN;
        manualUntil = 0;
        footer = synced ? "Letra: " + source : "Letra sem tempos · " + source;
        invalidate();
    }

    public void setPosition(long posMs, long durMs, boolean isPlaying) {
        anchorPos = posMs;
        anchorAt = SystemClock.uptimeMillis();
        durationMs = durMs;
        playing = isPlaying;
        invalidate();
    }

    private long pos() {
        long p = anchorPos;
        if (playing) p += SystemClock.uptimeMillis() - anchorAt;
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }

    private void build(int w) {
        int tw = Math.max(50, w - PAD_X * 2);
        normalL = new StaticLayout[lines.size()];
        bigL = new StaticLayout[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).text;
            if (t.isEmpty()) t = synced ? "♪" : " ";
            normalL[i] = layout(t, normal, tw);
            if (synced) bigL[i] = layout(t, big, tw);
        }
        builtWidth = w;
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout layout(CharSequence t, TextPaint p, int w) {
        return new StaticLayout(t, p, w, Layout.Alignment.ALIGN_NORMAL, 1.05f, 0f, false);
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout centered(CharSequence t, TextPaint p, int w) {
        return new StaticLayout(t, p, w, Layout.Alignment.ALIGN_CENTER, 1.1f, 0f, false);
    }

    /** Linha atual (a última que já começou), ou -1 antes da primeira. */
    private int current(long p) {
        int cur = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).t <= p) cur = i;
            else break;
        }
        return cur;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (message != null) {
            drawMessage(c, w, h);
            return;
        }
        if (lines.isEmpty()) return;
        if (builtWidth != w) build(w);

        long p = pos();
        int cur = synced ? current(p) : -1;
        int n = lines.size();
        float[] top = new float[n];
        float y = 0;
        for (int i = 0; i < n; i++) {
            top[i] = y;
            StaticLayout l = synced && i == cur ? bigL[i] : normalL[i];
            y += l.getHeight() + GAP;
        }
        float total = y;

        // Para onde a letra deve ir
        float target;
        if (synced) {
            if (cur < 0) target = -h * 0.42f + (normalL[0].getHeight() / 2f);
            else target = top[cur] + bigL[cur].getHeight() / 2f - h * 0.42f;
        } else {
            float f = durationMs > 0 ? Math.max(0f, Math.min(1f, p / (float) durationMs)) : 0f;
            target = -40 + f * Math.max(0f, total - h * 0.55f + 40);
        }
        long now = SystemClock.uptimeMillis();
        boolean manual = dragging || now < manualUntil;
        if (Float.isNaN(scroll)) scroll = target;
        boolean moving = false;
        if (!manual) {
            float dt = lastFrame == 0 ? 16 : Math.min(100, now - lastFrame);
            float k = (float) (1 - Math.exp(-dt / (synced ? 140.0 : 400.0)));
            float diff = target - scroll;
            if (Math.abs(diff) > 0.5f) {
                scroll += diff * k;
                moving = true;
            } else {
                scroll = target;
            }
        }
        lastFrame = now;

        // Desenha as linhas visíveis
        for (int i = 0; i < n; i++) {
            float ty = top[i] - scroll;
            StaticLayout l = synced && i == cur ? bigL[i] : normalL[i];
            if (ty + l.getHeight() < 0) continue;
            if (ty > h) break;
            int color;
            if (!synced) color = 0xFFC9CDD3;
            else if (i == cur) color = 0xFFF2F3F5;
            else if (i < cur) color = 0xFF5D636C;
            else if (i == cur + 1) color = 0xFF9AA0A6;
            else color = 0xFF6B717A;
            l.getPaint().setColor(color);
            c.save();
            c.translate(PAD_X, ty);
            l.draw(c);
            c.restore();
        }

        // Esbatido em cima e em baixo
        fade.setShader(new LinearGradient(0, 0, 0, 60, CARD, CARD & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, 60, fade);
        fade.setShader(new LinearGradient(0, h - 80, 0, h, CARD & 0x00FFFFFF, CARD, Shader.TileMode.CLAMP));
        c.drawRect(0, h - 80, w, h, fade);
        if (!footer.isEmpty()) c.drawText(footer, w - 22, h - 16, small);

        // Próximo desenho
        if (moving || manual) {
            postInvalidateOnAnimation();
        } else if (playing) {
            if (synced) {
                long next = cur + 1 < n ? lines.get(cur + 1).t - p : 1000;
                postInvalidateDelayed(Math.max(16, Math.min(1000, next)));
            } else {
                postInvalidateDelayed(50); // desce devagarinho
            }
        }
    }

    private void drawMessage(Canvas c, int w, int h) {
        int tw = Math.max(50, w - 80);
        if (msgL == null) {
            msgL = centered(message, msgTitle, tw);
            msgSubL = messageSub == null ? null : centered(messageSub, note, Math.min(tw, 440));
        }
        float block = 70 + msgL.getHeight() + (msgSubL != null ? 12 + msgSubL.getHeight() : 0);
        float y = (h - block) / 2f;
        note.setTextAlign(Paint.Align.CENTER);
        float size = note.getTextSize();
        int col = note.getColor();
        note.setTextSize(54);
        note.setColor(0xFF2C313A);
        c.drawText("♪", w / 2f, y + 52, note);
        note.setTextSize(size);
        note.setColor(col);
        note.setTextAlign(Paint.Align.LEFT);
        y += 70;
        c.save();
        c.translate((w - tw) / 2f, y);
        msgL.draw(c);
        c.restore();
        if (msgSubL != null) {
            c.save();
            c.translate((w - msgSubL.getWidth()) / 2f, y + msgL.getHeight() + 12);
            msgSubL.draw(c);
            c.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (message != null || lines.isEmpty()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                downScroll = Float.isNaN(scroll) ? 0 : scroll;
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                scroll = downScroll - (e.getY() - downY);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                manualUntil = SystemClock.uptimeMillis() + 5000; // volta sozinha passados 5 s
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
