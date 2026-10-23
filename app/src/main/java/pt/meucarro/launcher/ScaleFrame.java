package pt.meucarro.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Desenha o layout num "ecrã de design" com 600px de altura (o ecrã do rádio é 1024×600)
 * e escala-o para o tamanho real do ecrã. Assim o design fica igual em qualquer rádio,
 * seja qual for a resolução ou densidade.
 *
 * Em ecrãs estreitos (ex.: o emulador do Android Studio, que é um telemóvel ao alto)
 * escala pela largura, para o design nunca ficar com menos de 960px de largura;
 * nesse caso o layout fica mais alto e os blocos com peso (weight) esticam.
 */
public class ScaleFrame extends FrameLayout {

    public static final int DESIGN_HEIGHT = 600;
    public static final int MIN_DESIGN_WIDTH = 960;

    public ScaleFrame(Context context) {
        super(context);
    }

    public ScaleFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private static float scaleFor(int w, int h) {
        return Math.min(h / (float) DESIGN_HEIGHT, w / (float) MIN_DESIGN_WIDTH);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
        if (w == 0 || h == 0) return;

        float scale = scaleFor(w, h);
        int designWidth = Math.round(w / scale);
        int designHeight = Math.round(h / scale);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(designWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(designHeight, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        if (w == 0 || h == 0) return;
        float scale = scaleFor(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
            child.setPivotX(0f);
            child.setPivotY(0f);
            child.setScaleX(scale);
            child.setScaleY(scale);
        }
    }
}
