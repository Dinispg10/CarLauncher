package pt.meucarro.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Teste de ligação "como o adaptador OBD2": liga o rádio a outro aparelho por porta série
 * Bluetooth (SPP, o mesmo tipo de ligação do adaptador) e confirma se o telemóvel continua
 * ligado ao mesmo tempo. Para testar com o PC, o Windows tem de ter uma porta COM "de entrada".
 */
@SuppressLint("MissingPermission")
public class SppTestActivity extends Activity {

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private BluetoothAdapter adapter;
    private LinearLayout list;
    private TextView log;
    private int accent;
    private BluetoothDevice pendingBond;
    private boolean busy;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (BluetoothDevice.ACTION_FOUND.equals(action) && d != null) {
                devices.put(d.getAddress(), d);
                renderList();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                add("Procura terminada.");
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action) && d != null) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (state == BluetoothDevice.BOND_BONDING) add("A emparelhar com " + name(d) + "… confirme no rádio e no PC.");
                if (state == BluetoothDevice.BOND_BONDED) {
                    add("Emparelhado com " + name(d) + ".");
                    devices.put(d.getAddress(), d);
                    renderList();
                    if (pendingBond != null && pendingBond.getAddress().equals(d.getAddress())) {
                        pendingBond = null;
                        connect(d);
                    }
                } else if (state == BluetoothDevice.BOND_NONE && pendingBond != null
                        && pendingBond.getAddress().equals(d.getAddress())) {
                    pendingBond = null;
                    add("✗ O emparelhamento com " + name(d) + " não foi aceite.");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accent = getSharedPreferences("launcher", MODE_PRIVATE).getInt("accent", 0xFF5EEAD4);
        adapter = BluetoothAdapter.getDefaultAdapter();

        ScaleFrame frame = new ScaleFrame(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0D10);
        root.setPadding(36, 20, 36, 20);
        frame.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text("Teste de ligação (como o adaptador)", "600", 28, 0xFFF2F3F5),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(button("Tornar o rádio visível", false, v -> makeVisible()));
        View gap0 = new View(this);
        top.addView(gap0, new LinearLayout.LayoutParams(12, 1));
        top.addView(button("Procurar aparelhos", false, v -> discover()));
        View gap = new View(this);
        top.addView(gap, new LinearLayout.LayoutParams(12, 1));
        top.addView(button("Sair", true, v -> finish()));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56));

        TextView help = text("1) No PC, crie uma porta COM \"de entrada\" (ver as instruções do Claude).  "
                + "2) Telemóvel ligado ao rádio a tocar música.  "
                + "3) Carregue em \"Procurar aparelhos\" e toque no nome do PC. "
                + "Aceite o emparelhamento no rádio e no PC. O teste corre sozinho.", "500", 16, 0xFF9AA0A6);
        help.setLineSpacing(3f, 1f);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        helpLp.topMargin = 8;
        root.addView(help, helpLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = 12;
        root.addView(body, bodyLp);

        ScrollView listScroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list);
        body.addView(listScroll, new LinearLayout.LayoutParams(360, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView logScroll = new ScrollView(this);
        log = text("", "500", 17, 0xFFF2F3F5);
        log.setLineSpacing(4f, 1f);
        log.setPadding(22, 16, 22, 16);
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(22f);
        card.setColor(0xFF13161B);
        logScroll.setBackground(card);
        logScroll.addView(log);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        logLp.leftMargin = 16;
        body.addView(logScroll, logLp);

        setContentView(frame);

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(receiver, f);

        if (adapter == null || !adapter.isEnabled()) {
            add("✗ O Bluetooth do Android está desligado ou não existe.");
            return;
        }
        add("Telemóvel ligado (música): " + yesNo(phoneAudio()) + " · (chamadas): " + yesNo(phoneCalls()));
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) devices.put(d.getAddress(), d);
        } catch (Exception ignored) {
        }
        renderList();
        add("Aparelhos já emparelhados à esquerda. Se o PC não estiver, carregue em \"Procurar aparelhos\".");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        try {
            if (adapter != null) adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }
    }

    // ---------- Lista e procura ----------

    private void discover() {
        if (adapter == null) return;
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            add(adapter.startDiscovery() ? "A procurar aparelhos (12 s)… o PC tem de estar visível."
                    : "✗ Não foi possível procurar.");
        } catch (Exception e) {
            add("✗ Erro ao procurar: " + e.getMessage());
        }
    }

    private void renderList() {
        list.removeAllViews();
        for (BluetoothDevice d : devices.values()) {
            boolean bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
            TextView row = text(name(d) + (bonded ? "\nemparelhado" : "\nnovo"), "600", 18, 0xFFF2F3F5);
            row.setPadding(20, 10, 16, 10);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(18f);
            bg.setColor(0xFF13161B);
            bg.setStroke(1, 0xFF2C313A);
            row.setBackground(bg);
            row.setOnClickListener(v -> onDevice(d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            list.addView(row, lp);
        }
    }

    private void onDevice(BluetoothDevice d) {
        if (busy) return;
        busy = true;
        boolean wasDiscovering = false;
        try {
            wasDiscovering = adapter.isDiscovering();
            adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }
        if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
            busy = false;
            connect(d);
            return;
        }
        // Alguns rádios recusam emparelhar enquanto ainda estão a procurar: espera um pouco
        if (wasDiscovering) add("A parar a procura…");
        main.postDelayed(() -> {
            busy = false;
            add("A emparelhar com " + name(d) + "… aceite no rádio e no PC.");
            pendingBond = d;
            if (bond(d)) return;
            // O rádio recusou o pedido de emparelhamento: tenta ligar diretamente,
            // o Android emparelha durante a ligação (é assim que muitas apps OBD fazem)
            pendingBond = null;
            add("O rádio recusou o pedido de emparelhamento. A tentar ligar diretamente…");
            connect(d);
        }, wasDiscovering ? 2000 : 300);
    }

    /** Pede o emparelhamento pela forma normal e, se falhar, pela forma "Bluetooth clássico". */
    private boolean bond(BluetoothDevice d) {
        try {
            if (d.createBond()) return true;
        } catch (Exception ignored) {
        }
        try {
            Method m = d.getClass().getMethod("createBond", int.class);
            Object ok = m.invoke(d, 1); // 1 = TRANSPORT_BREDR (Bluetooth clássico)
            if (Boolean.TRUE.equals(ok)) {
                add("(emparelhamento pedido pela forma alternativa)");
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Deixa o PC encontrar o rádio durante 2 minutos (para emparelhar a partir do PC). */
    private void makeVisible() {
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            startActivity(i);
            add("Rádio visível durante 2 minutos com o nome \"" + adapter.getName() + "\". No PC: Definições → "
                    + "Bluetooth → Adicionar dispositivo → Bluetooth → escolha o rádio e confirme o código. "
                    + "Depois volte aqui, carregue em \"Procurar aparelhos\" e toque no PC.");
        } catch (Exception e) {
            add("✗ O rádio não deixou ficar visível: " + e.getMessage());
        }
    }

    // ---------- Ligação por porta série (igual à do adaptador) ----------

    private void connect(BluetoothDevice d) {
        busy = true;
        add("A ligar a " + name(d) + " por porta série…");
        new Thread(() -> {
            BluetoothSocket socket = open(d);
            if (socket == null) {
                main.post(() -> {
                    busy = false;
                    add("✗ Não ligou. No PC confirme que existe uma porta COM \"de entrada\" "
                            + "e repita. Se continuar a falhar, diga ao Claude.");
                });
                return;
            }
            boolean audioBefore = phoneAudio();
            String reply = "";
            try {
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                out.write("ATZ\r\nOla do Launcher do Carro!\r\n".getBytes("UTF-8"));
                out.flush();
                long end = System.currentTimeMillis() + 3000;
                StringBuilder sb = new StringBuilder();
                while (System.currentTimeMillis() < end) {
                    if (in.available() > 0) {
                        byte[] buf = new byte[256];
                        int n = in.read(buf);
                        if (n > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                    }
                    Thread.sleep(100);
                }
                reply = sb.toString().trim();
            } catch (Exception e) {
                reply = "(erro a enviar: " + e.getMessage() + ")";
            }
            boolean audioDuring = phoneAudio();
            boolean callsDuring = phoneCalls();
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            final String r = reply;
            main.post(() -> {
                busy = false;
                add("✓ LIGADO a " + name(d) + " por porta série. Mensagem enviada.");
                if (!r.isEmpty()) add("Resposta recebida: " + r);
                add("Telemóvel durante a ligação → música: " + yesNo(audioDuring)
                        + " · chamadas: " + yesNo(callsDuring));
                if (audioBefore && audioDuring) {
                    add("RESULTADO: ✓ o rádio aguenta o telemóvel e uma ligação de porta série ao mesmo tempo. "
                            + "O adaptador OBD2 deve funcionar.");
                } else if (!audioBefore) {
                    add("RESULTADO: ? a ligação funcionou, mas o telemóvel não estava ligado à música. "
                            + "Ligue o telemóvel, ponha música e repita.");
                } else {
                    add("RESULTADO: ✗ o telemóvel desligou-se durante a ligação. O adaptador pode dar conflito.");
                }
                AudioLog.add("Teste porta série com " + name(d) + ": música antes=" + audioBefore
                        + " durante=" + audioDuring);
            });
        }).start();
    }

    /** Tenta as três formas habituais de abrir a porta série. */
    private BluetoothSocket open(BluetoothDevice d) {
        String[] ways = {"segura", "sem segurança", "canal 1"};
        for (int i = 0; i < ways.length; i++) {
            BluetoothSocket s = null;
            try {
                if (i == 0) s = d.createRfcommSocketToServiceRecord(SPP);
                else if (i == 1) s = d.createInsecureRfcommSocketToServiceRecord(SPP);
                else {
                    Method m = d.getClass().getMethod("createRfcommSocket", int.class);
                    s = (BluetoothSocket) m.invoke(d, 1);
                }
                s.connect();
                final String way = ways[i];
                main.post(() -> add("Ligação aberta (" + way + ")."));
                return s;
            } catch (Exception e) {
                final String way = ways[i];
                final String msg = e.getMessage();
                main.post(() -> add("· tentativa " + way + " falhou: " + msg));
                try {
                    if (s != null) s.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    // ---------- Ajudas ----------

    private boolean phoneAudio() {
        return adapter != null && (BluetoothTest.connected(adapter, 11) || BluetoothTest.connected(adapter, BluetoothProfile.A2DP));
    }

    private boolean phoneCalls() {
        return adapter != null && (BluetoothTest.connected(adapter, 16) || BluetoothTest.connected(adapter, BluetoothProfile.HEADSET));
    }

    private static String yesNo(boolean b) {
        return b ? "sim" : "não";
    }

    private static String name(BluetoothDevice d) {
        String n = null;
        try {
            n = d.getName();
        } catch (Exception ignored) {
        }
        return n != null ? n : d.getAddress();
    }

    private void add(String line) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        CharSequence old = log.getText();
        log.setText(time + "  " + line + (old.length() > 0 ? "\n" + old : ""));
    }

    private TextView text(String s, String weight, int sizePx, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Ui.font(this, weight));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String label, boolean primary, View.OnClickListener click) {
        TextView b = text(label, "600", 17, primary ? 0xFF0B0D10 : 0xFFF2F3F5);
        b.setGravity(Gravity.CENTER);
        b.setPadding(24, 0, 24, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(24f);
        bg.setColor(primary ? accent : 0xFF1E2228);
        b.setBackground(bg);
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48));
        return b;
    }
}
