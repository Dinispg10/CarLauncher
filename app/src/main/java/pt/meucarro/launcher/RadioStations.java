package pt.meucarro.launcher;

/**
 * As rádios do ecrã Rádio. Cada uma tem várias ligações: se a primeira falhar,
 * a app tenta a seguinte. Para mudar ou acrescentar rádios, é só editar esta lista.
 */
public final class RadioStations {

    public static final class Station {
        public final String id;
        public final String name;
        public final String genre;
        public final int color;
        public final String[] urls;

        Station(String id, String name, String genre, int color, String... urls) {
            this.id = id;
            this.name = name;
            this.genre = genre;
            this.color = color;
            this.urls = urls;
        }
    }

    private static final String STW = "https://playerservices.streamtheworld.com/api/livestream-redirect/";

    public static final Station[] ALL = {
            new Station("rfm", "RFM", "Pop e hits", 0xFFE30E2E,
                    STW + "RFMAAC.aac",
                    "https://23603.live.streamtheworld.com/RFMAAC.aac",
                    STW + "RFM.mp3"),
            new Station("comercial", "Comercial", "Pop e hits", 0xFFE8452C,
                    "https://stream-icy.bauermedia.pt/comercial.aac",
                    "https://stream-hls.bauermedia.pt/comercial.aac/playlist.m3u8",
                    "https://stream-icy.bauermedia.pt/comercial.mp3"),
            new Station("m80", "M80", "Clássicos 70 · 80 · 90", 0xFFE8333A,
                    "https://stream-icy.bauermedia.pt/m80.aac",
                    "https://stream-hls.bauermedia.pt/m80.aac/playlist.m3u8",
                    "https://stream-icy.bauermedia.pt/m80.mp3"),
            new Station("cidade", "Cidade FM", "Hits e hip-hop", 0xFFFFE600,
                    "https://stream-icy.bauermedia.pt/cidade.aac",
                    "https://stream-hls.bauermedia.pt/cidade.aac/playlist.m3u8",
                    "https://stream-icy.bauermedia.pt/cidade.mp3"),
            new Station("megahits", "Mega Hits", "Top 40 e novidades", 0xFFF47F24,
                    STW + "MEGA_HITSAAC.aac",
                    "https://22533.live.streamtheworld.com/MEGA_HITSAAC.aac",
                    STW + "MEGA_HITS.mp3"),
            new Station("renascenca", "Renascença", "Notícias e música", 0xFF3E7BFA,
                    STW + "RADIO_RENASCENCA.mp3",
                    STW + "RADIO_RENASCENCAAAC.aac",
                    STW + "RENASCENCA.mp3"),
            new Station("antena1", "Antena 1", "RTP · Generalista", 0xFFD3151C,
                    "https://streaming-live.rtp.pt/liveradio/antena180a/playlist.m3u8",
                    "https://radiocast.rtp.pt/antena180a.mp3"),
            new Station("antena3", "Antena 3", "RTP · Alternativa", 0xFFF97A12,
                    "https://streaming-live.rtp.pt/liveradio/antena380a/playlist.m3u8",
                    "https://radiocast.rtp.pt/antena380a.mp3"),
            new Station("tsf", "TSF", "Notícias", 0xFF2A55C8,
                    "https://directo.tsf.pt/tsfdirecto.mp3",
                    "https://tsfdirecto.tsf.pt/tsfdirecto.mp3"),
    };

    private RadioStations() {
    }

    // ---------- Logótipos ----------
    // Para pôr o logótipo de uma rádio: imagem quadrada em app/src/main/res/drawable-nodpi/
    // com o nome radio_<id>.png (ex.: radio_rfm.png, radio_antena1.png).
    // Sem logótipo, a app desenha um quadrado com a cor e o nome da rádio.

    /** Recurso do logótipo, ou 0 se não existir. */
    public static int logoRes(android.content.Context context, Station station) {
        return context.getResources().getIdentifier("radio_" + station.id, "drawable", context.getPackageName());
    }

    private static final android.graphics.Bitmap[] ART = new android.graphics.Bitmap[ALL.length];

    /** "Capa" da rádio (logótipo, ou cor + nome), quadrada, 400×400. */
    public static android.graphics.Bitmap art(android.content.Context context, int index) {
        if (index < 0 || index >= ALL.length) return null;
        if (ART[index] != null) return ART[index];
        Station station = ALL[index];
        int size = 400;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        int res = logoRes(context, station);
        android.graphics.Bitmap logo = null;
        if (res != 0) {
            try {
                logo = android.graphics.BitmapFactory.decodeResource(context.getResources(), res);
            } catch (Throwable ignored) {
            }
        }
        if (logo != null) {
            // Fundo com a cor do canto do logótipo, para logótipos que não são quadrados
            canvas.drawColor(logo.getPixel(0, 0) == 0 ? 0xFFFFFFFF : logo.getPixel(0, 0));
            float scale = Math.min(size / (float) logo.getWidth(), size / (float) logo.getHeight());
            int w = Math.round(logo.getWidth() * scale);
            int h = Math.round(logo.getHeight() * scale);
            android.graphics.Rect dst = new android.graphics.Rect((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2);
            canvas.drawBitmap(logo, null, dst, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        } else {
            canvas.drawColor(station.color);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFFFFFF);
            paint.setTypeface(Ui.font(context, "700"));
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            float textSize = 120f;
            paint.setTextSize(textSize);
            while (paint.measureText(station.name) > size * 0.82f && textSize > 40f) {
                textSize -= 4f;
                paint.setTextSize(textSize);
            }
            android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(station.name, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint);
        }
        ART[index] = bitmap;
        return bitmap;
    }
}
