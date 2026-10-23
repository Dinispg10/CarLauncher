package pt.meucarro.launcher;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * Registo de som: guarda os últimos 60 acontecimentos (rádio, Bluetooth, foco de áudio)
 * com a hora, para perceber porque é que o som falha. Vê-se nas definições.
 */
public final class AudioLog {

    private static final int MAX = 60;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private AudioLog() {
    }

    public static synchronized void add(String event) {
        LINES.addFirst(TIME.format(new Date()) + "  " + event);
        while (LINES.size() > MAX) LINES.removeLast();
    }

    /** Mais recente primeiro. */
    public static synchronized String dump() {
        if (LINES.isEmpty()) return "Ainda não aconteceu nada desde que o rádio ligou.";
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = LINES.iterator();
        while (it.hasNext()) sb.append(it.next()).append('\n');
        return sb.toString().trim();
    }

    public static synchronized void clear() {
        LINES.clear();
    }

    public static String focusName(int change) {
        switch (change) {
            case 1:
                return "ganhou o som";
            case -1:
                return "PERDEU o som (outra app)";
            case -2:
                return "perdeu o som por momentos";
            case -3:
                return "som baixado por momentos";
            default:
                return "foco " + change;
        }
    }
}
