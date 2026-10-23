package pt.meucarro.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;

import java.util.List;

/**
 * Teste do telefone: vê se a app de Bluetooth do rádio aceita pedidos de chamada de outras apps
 * e se a agenda / últimas chamadas do telemóvel ficam disponíveis no Android.
 * Não faz chamadas: o botão de experiência só abre o marcador com "123" escrito.
 */
public final class PhoneTest {

    public static final String[] PERMISSIONS = {Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG};

    private PhoneTest() {
    }

    public static boolean hasPermissions(Activity a) {
        for (String p : PERMISSIONS) {
            if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    public static void run(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        StringBuilder out = new StringBuilder();

        Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:123"));
        Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:123"));
        List<ResolveInfo> dialers = pm.queryIntentActivities(dial, 0);
        List<ResolveInfo> callers = pm.queryIntentActivities(call, 0);
        out.append("Apps que aceitam \"marcar número\": ").append(names(pm, dialers)).append('\n');
        out.append("Apps que aceitam \"ligar já\": ").append(names(pm, callers)).append("\n\n");

        int contacts = count(activity, ContactsContract.Contacts.CONTENT_URI);
        int calls = count(activity, CallLog.Calls.CONTENT_URI);
        out.append("Contactos no Android: ").append(contacts < 0 ? "sem autorização" : contacts).append('\n');
        out.append("Últimas chamadas no Android: ").append(calls < 0 ? "sem autorização" : calls).append("\n\n");

        boolean canDial = !dialers.isEmpty() || !callers.isEmpty();
        if (canDial && contacts > 0) {
            out.append("RESULTADO: ✓ dá para fazer o ecrã Telefone com favoritos, agenda e marcação.");
        } else if (canDial) {
            out.append("RESULTADO: ✓ dá para fazer o ecrã Telefone com favoritos e teclado. "
                    + "A agenda não está no Android (o rádio guarda-a só na app dele), "
                    + "por isso os favoritos teriam de ser escritos à mão.");
        } else {
            out.append("RESULTADO: ✗ nenhuma app do rádio aceita pedidos de chamada. "
                    + "O ecrã Telefone nosso não conseguiria ligar.");
        }
        out.append("\n\nPara confirmar, carregue em \"Experimentar\": deve abrir a app de Bluetooth "
                + "com 123 escrito, SEM ligar. Depois volte atrás.");

        Sheet.create(activity, "Teste do telefone").width(860)
                .message(out.toString())
                .button("Fechar", false, null)
                .button("Experimentar", true, () -> {
                    AudioLog.add("Teste do telefone: abrir marcador com 123");
                    try {
                        activity.startActivity(dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        Sheet.message(activity, "Teste do telefone",
                                "Nenhuma app abriu o marcador: " + e.getMessage());
                    }
                })
                .show();
    }

    private static String names(PackageManager pm, List<ResolveInfo> list) {
        if (list == null || list.isEmpty()) return "nenhuma";
        StringBuilder sb = new StringBuilder();
        for (ResolveInfo r : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.loadLabel(pm)).append(" (").append(r.activityInfo.packageName).append(')');
        }
        return sb.toString();
    }

    private static int count(Activity a, Uri uri) {
        try (Cursor c = a.getContentResolver().query(uri, null, null, null, null)) {
            return c == null ? 0 : c.getCount();
        } catch (SecurityException e) {
            return -1;
        } catch (Exception e) {
            return 0;
        }
    }
}
