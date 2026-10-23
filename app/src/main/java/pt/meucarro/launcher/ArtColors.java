package pt.meucarro.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Tira a cor mais marcante de uma capa de álbum, ajustada para ler bem num fundo escuro. */
public final class ArtColors {

    private ArtColors() {
    }

    /** Devolve a cor de destaque, ou null se a capa for quase toda cinzenta/preta/branca. */
    public static Integer accentFrom(Bitmap art) {
        if (art == null) return null;
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(art, 32, 32, true);
        } catch (Exception e) {
            return null;
        }

        // 12 "fatias" de cor; cada píxel vota na sua com peso = saturação × brilho
        float[] weight = new float[12];
        float[] sumR = new float[12], sumG = new float[12], sumB = new float[12];
        float[] hsv = new float[3];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int c = small.getPixel(x, y);
                Color.colorToHSV(c, hsv);
                float s = hsv[1], v = hsv[2];
                if (s < 0.25f || v < 0.25f) continue; // ignora cinzentos e sombras
                int bucket = Math.min(11, (int) (hsv[0] / 30f));
                float w = s * v * v;
                weight[bucket] += w;
                sumR[bucket] += Color.red(c) * w;
                sumG[bucket] += Color.green(c) * w;
                sumB[bucket] += Color.blue(c) * w;
            }
        }
        if (small != art) small.recycle();

        int best = -1;
        float total = 0f;
        for (int i = 0; i < 12; i++) {
            total += weight[i];
            if (best < 0 || weight[i] > weight[best]) best = i;
        }
        if (best < 0 || weight[best] < 8f) return null; // capa sem cor suficiente

        int color = Color.rgb(
                Math.round(sumR[best] / weight[best]),
                Math.round(sumG[best] / weight[best]),
                Math.round(sumB[best] / weight[best]));

        // Ajusta para ficar viva mas legível sobre #0B0D10, com o ícone escuro por cima
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0.40f, Math.min(0.80f, hsv[1]));
        hsv[2] = Math.max(0.85f, hsv[2]);
        return Color.HSVToColor(hsv);
    }
}
