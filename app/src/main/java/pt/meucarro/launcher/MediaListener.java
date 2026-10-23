package pt.meucarro.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Não lê notificações. Só existe porque o Android exige um "ouvinte de
 * notificações" autorizado para deixar uma app ver a música que está a tocar.
 */
public class MediaListener extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        MediaHub.get(this).start();
    }
}
