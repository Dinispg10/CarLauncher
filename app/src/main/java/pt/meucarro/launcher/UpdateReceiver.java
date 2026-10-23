package pt.meucarro.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Recebe o resultado da instalação de uma atualização. */
public class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // O Android pede confirmação ("Instalar") — abre esse ecrã
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                msg = "a atualização foi assinada com outra chave (ver LEIA-ME, secção Atualizações)";
            }
            Toast.makeText(context, "A atualização falhou: " + (msg != null ? msg : "erro " + status),
                    Toast.LENGTH_LONG).show();
        }
    }
}
