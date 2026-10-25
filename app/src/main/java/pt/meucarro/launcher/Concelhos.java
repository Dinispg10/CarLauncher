package pt.meucarro.launcher;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Os 308 concelhos de Portugal (contornos simplificados da CAOP, Direção-Geral do Território).
 * Diz em que concelho fica um ponto. Coordenadas guardadas em décimos de milésimo de grau.
 */
public final class Concelhos {

    public static final int TOTAL = 308;

    public static final class Concelho {
        public final String name, district;
        public final List<int[]> rings = new ArrayList<>(); // cada anel: x0,y0,x1,y1… (lon,lat ×1e4)
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        Concelho(String name, String district) {
            this.name = name;
            this.district = district;
        }

        /** Identificador único ("Lagoa" existe no Algarve e nos Açores). */
        public String key() {
            return name + "|" + district;
        }

        boolean contains(int x, int y) {
            if (x < minX || x > maxX || y < minY || y > maxY) return false;
            boolean in = false;
            for (int[] r : rings) {
                int n = r.length / 2;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    int xi = r[i * 2], yi = r[i * 2 + 1], xj = r[j * 2], yj = r[j * 2 + 1];
                    if ((yi > y) != (yj > y) && x < (double) (xj - xi) * (y - yi) / (double) (yj - yi) + xi) in = !in;
                }
            }
            return in;
        }
    }

    private static List<Concelho> all;
    private static Concelho last;

    private Concelhos() {
    }

    public static synchronized List<Concelho> all(Context c) {
        if (all != null) return all;
        List<Concelho> list = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open("concelhos.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3) continue;
                Concelho k = new Concelho(parts[0], parts[1]);
                for (String ring : parts[2].split(";")) {
                    String[] pts = ring.trim().split(" ");
                    int[] xy = new int[pts.length * 2];
                    int x = 0, y = 0;
                    for (int i = 0; i < pts.length; i++) {
                        int comma = pts[i].indexOf(',');
                        x += Integer.parseInt(pts[i].substring(0, comma));
                        y += Integer.parseInt(pts[i].substring(comma + 1));
                        xy[i * 2] = x;
                        xy[i * 2 + 1] = y;
                        k.minX = Math.min(k.minX, x);
                        k.maxX = Math.max(k.maxX, x);
                        k.minY = Math.min(k.minY, y);
                        k.maxY = Math.max(k.maxY, y);
                    }
                    k.rings.add(xy);
                }
                list.add(k);
            }
        } catch (Exception ignored) {
        }
        all = list;
        return all;
    }

    /** Concelho onde fica o ponto, ou null (mar, Espanha…). */
    public static Concelho find(Context c, double lat, double lon) {
        int x = (int) Math.round(lon * 1e4), y = (int) Math.round(lat * 1e4);
        Concelho l = last;
        if (l != null && l.contains(x, y)) return l;
        for (Concelho k : all(c)) {
            if (k.contains(x, y)) {
                last = k;
                return k;
            }
        }
        return null;
    }
}
