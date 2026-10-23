package pt.meucarro.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Teste de Bluetooth: com o telemóvel ligado ao rádio, procura aparelhos Bluetooth
 * (clássico e BLE) durante 12 segundos. No Android, os adaptadores OBD2 usam o clássico
 * (o Vgate iCar Pro aparece como "Android-Vlink"). Se encontrar aparelhos, o rádio
 * consegue procurar o adaptador sem largar o telemóvel.
 * Só procura; não emparelha nem liga a nada.
 */
public final class BluetoothTest {

    private static final long SCAN_MS = 12_000;

    private BluetoothTest() {
    }

    @SuppressLint("MissingPermission")
    public static void run(Activity activity) {
        StringBuilder head = new StringBuilder();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Sheet.message(activity, "Teste de Bluetooth",
                    "O Android deste rádio não tem Bluetooth próprio: o Bluetooth do telemóvel é um módulo à parte.\n\n"
                            + "Resultado: o adaptador OBD2 por Bluetooth NÃO vai funcionar com o rádio. "
                            + "Não compre o adaptador para isto.");
            return;
        }
        boolean ble = activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        head.append("Bluetooth do Android: ").append(adapter.isEnabled() ? "ligado" : "DESLIGADO").append('\n');
        head.append("Bluetooth de baixo consumo (BLE): ").append(ble ? "sim" : "não").append('\n');
        head.append("Telemóvel ligado (música): ").append(connected(adapter, 11) || connected(adapter, BluetoothProfile.A2DP)
                ? "sim" : "não detetado").append('\n');
        head.append("Telemóvel ligado (chamadas): ").append(connected(adapter, 16) || connected(adapter, BluetoothProfile.HEADSET)
                ? "sim" : "não detetado").append('\n');
        try {
            int paired = adapter.getBondedDevices() != null ? adapter.getBondedDevices().size() : 0;
            head.append("Aparelhos emparelhados: ").append(paired).append('\n');
        } catch (Exception ignored) {
        }

        if (!adapter.isEnabled()) {
            Sheet.message(activity, "Teste de Bluetooth", head
                    + "\nO Bluetooth do Android está desligado. Ligue-o e repita o teste.");
            return;
        }

        Toast.makeText(activity, "A procurar aparelhos Bluetooth durante 12 segundos… "
                + "(a música pode falhar um instante)", Toast.LENGTH_LONG).show();

        // 1) Bluetooth clássico: é o que os adaptadores OBD2 usam no Android ("Android-Vlink", OBDLink LX…)
        final Map<String, String> classic = new LinkedHashMap<>();
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                String name = null;
                try {
                    name = d.getName();
                } catch (Exception ignored) {
                }
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                classic.put(d.getAddress(), (name != null ? name : "(sem nome)")
                        + (rssi != Short.MIN_VALUE ? "  ·  " + rssi + " dBm" : ""));
            }
        };
        boolean classicStarted = false;
        try {
            activity.registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            classicStarted = adapter.startDiscovery();
        } catch (Exception ignored) {
        }

        // 2) BLE: tenta mesmo que o rádio diga que não suporta (alguns rádios dizem mal)
        final Map<String, String> le = new LinkedHashMap<>();
        BluetoothLeScanner scanner = null;
        ScanCallback callback = null;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) {
                callback = new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        BluetoothDevice d = result.getDevice();
                        String name = null;
                        try {
                            name = d.getName();
                        } catch (Exception ignored) {
                        }
                        if (name == null && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
                        le.put(d.getAddress(), (name != null ? name : "(sem nome)") + "  ·  " + result.getRssi() + " dBm");
                    }
                };
                scanner.startScan(callback);
            }
        } catch (Exception e) {
            scanner = null;
        }

        final boolean startedClassic = classicStarted;
        final BluetoothLeScanner usedScanner = scanner;
        final ScanCallback usedCallback = callback;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                adapter.cancelDiscovery();
            } catch (Exception ignored) {
            }
            try {
                activity.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            try {
                if (usedScanner != null && usedCallback != null) usedScanner.stopScan(usedCallback);
            } catch (Exception ignored) {
            }
            if (activity.isFinishing()) return;

            StringBuilder msg = new StringBuilder(head);
            msg.append("\nBluetooth clássico: ");
            if (!startedClassic) {
                msg.append("não foi possível procurar\n");
            } else {
                msg.append(classic.size()).append(" encontrados\n");
                int n = 0;
                for (String line : classic.values()) {
                    if (n++ >= 8) break;
                    msg.append("• ").append(line).append('\n');
                }
            }
            msg.append("BLE: ").append(usedScanner == null ? "não disponível" : le.size() + " encontrados").append("\n\n");

            if (startedClassic && !classic.isEmpty()) {
                msg.append("RESULTADO: ✓ o rádio encontra aparelhos Bluetooth (clássico) com o telemóvel ligado. "
                        + "Adaptadores OBD2 que no Android usam Bluetooth clássico devem funcionar: "
                        + "o Vgate iCar Pro (aparece como \"Android-Vlink\") ou o OBDLink LX.");
            } else if (startedClassic) {
                msg.append("RESULTADO: ? não encontrou nenhum aparelho clássico. Ponha a coluna em modo de "
                        + "emparelhamento (luz a piscar) ao lado do rádio e repita o teste.");
            } else {
                msg.append("RESULTADO: ✗ o rádio não deixa procurar aparelhos Bluetooth com o telemóvel ligado. "
                        + "O adaptador OBD2 por Bluetooth provavelmente não vai funcionar.");
            }
            Sheet.create(activity, "Teste de Bluetooth").width(840).message(msg.toString().trim())
                    .button("OK", true, null).show();
        }, SCAN_MS);
    }

    @SuppressLint("MissingPermission")
    static boolean connected(BluetoothAdapter adapter, int profile) {
        try {
            return adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception e) {
            return false;
        }
    }
}
