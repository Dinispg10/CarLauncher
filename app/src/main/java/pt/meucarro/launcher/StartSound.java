package pt.meucarro.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;

import java.io.File;

/** Som curto quando o carro liga (junto com a animação "Boa noite, Dinis"). */
public final class StartSound {

    public static final String[] IDS = {"off", "suave", "premium", "digital", "classico", "custom"};
    public static final String[] NAMES = {"Desligado", "Suave", "Premium", "Digital", "Clássico", "O meu som"};
    public static final String CUSTOM_FILE = "start_sound_custom";

    private static MediaPlayer player;
    private static AudioFocusRequest focus;

    private StartSound() {
    }

    public static String choice(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("start_sound", "premium");
    }

    public static void setChoice(Context c, String id) {
        c.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("start_sound", id).apply();
    }

    public static String name(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return NAMES[i];
        return NAMES[0];
    }

    /** Toca o som escolhido nas definições. */
    public static void play(Context c) {
        play(c, choice(c));
    }

    public static void play(Context context, String id) {
        if ("off".equals(id)) return;
        Context c = context.getApplicationContext();
        stop(c);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            if ("custom".equals(id)) {
                File f = new File(c.getFilesDir(), CUSTOM_FILE);
                if (!f.exists()) return;
                mp.setDataSource(f.getAbsolutePath());
            } else {
                int res = c.getResources().getIdentifier("start_" + id, "raw", c.getPackageName());
                if (res == 0) return;
                android.content.res.AssetFileDescriptor afd = c.getResources().openRawResourceFd(res);
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            }
            // Baixa a música do telemóvel por um instante em vez de a parar
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= 26) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs).build();
                am.requestAudioFocus(focus);
            }
            mp.setOnCompletionListener(p -> stop(c));
            mp.setOnErrorListener((p, w, e) -> {
                stop(c);
                return true;
            });
            mp.prepare();
            // O som pessoal toca no máximo 8 segundos
            if ("custom".equals(id)) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (player == mp) stop(c);
                }, 8000);
            }
            mp.start();
            player = mp;
        } catch (Exception e) {
            stop(c);
        }
    }

    private static void stop(Context c) {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (focus != null && Build.VERSION.SDK_INT >= 26) {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.abandonAudioFocusRequest(focus);
            focus = null;
        }
    }
}
