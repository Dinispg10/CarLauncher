package pt.meucarro.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class MainActivity extends Activity implements MediaHub.Listener, TripService.Listener, Obd.Listener,
        Explore.Listener,
        RadioService.Listener {

    // Botões configuráveis da barra
    private static final String[] SLOTS = {"maps", "music", "radio", "phone"};
    private static final int[] SLOT_IDS = {
            R.id.dock_maps, R.id.dock_music, R.id.dock_radio, R.id.dock_phone};
    private static final String[] SLOT_NAMES = {"Mapas", "Música", "Rádio", "Telefone"};
    // Palavras usadas para encontrar sozinho a app certa de cada botão
    private static final String[][] SLOT_KEYWORDS = {
            {"com.google.android.apps.maps", "waze", "google maps", "maps", "mapas", "sygic", "igo", "navi"},
            {"música", "musica", "music", "spotify", "player"},
            {"rádio", "radio", "fm"},
            {"bluetooth", "telefone", "phone", "bt"},
    };

    private static final int[] ACCENTS = {0xFF5EEAD4, 0xFF7AA2FF, 0xFFFF8A3D, 0xFFF2F3F5};
    private static final String[] ACCENT_NAMES = {"Verde-água", "Azul", "Laranja", "Branco"};

    private static final int REQ_PHOTO = 1;
    private static final int REQ_SOUND = 9;
    private static final int REQ_LOCATION = 2;
    private static final String PHOTO_FILE = "car_photo";
    public static final String EXTRA_OPEN_SETTINGS = "open_settings";

    private static boolean coldStart = true; // primeira abertura desde que o rádio arrancou

    private SharedPreferences prefs;
    private MediaHub hub;
    private Bitmap carBitmap;
    private View rootView;
    private int currentAccent;
    private LocationManager locationManager;
    private TextView speedView;
    private boolean listening;
    private Location lastFix;
    private long lastWeatherAttempt;
    private final android.os.Handler weatherHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long lastLocationSave;
    private final Runnable nightTick = new Runnable() {
        @Override
        public void run() {
            applyNight();
            weatherHandler.postDelayed(this, 60 * 1000L);
        }
    };
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            maybeUpdateWeather();
            renderWeather();
            weatherHandler.postDelayed(this, 10 * 60 * 1000L);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (speedView == null) return;
            float kmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
            if (kmh < 2f) kmh = 0f; // ignora o "tremer" do GPS parado
            speedView.setText(String.valueOf(Math.round(kmh)));
            boolean firstFix = lastFix == null;
            lastFix = location;
            long now = System.currentTimeMillis();
            if (firstFix || now - lastLocationSave > 10 * 60 * 1000L) {
                Night.saveLocation(MainActivity.this, location);
                lastLocationSave = now;
            }
            if (firstFix) maybeUpdateWeather();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
            if (speedView != null) speedView.setText("--");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        hub = MediaHub.get(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        if (!prefs.getBoolean("detected", false)) {
            detectApps(false);
        }
        buildUi();
        CanBox.start(this);
        if (coldStart) {
            coldStart = false;
            carStarted();
            // Vê se há versão nova no GitHub (no máximo de 6 em 6 horas), depois da animação
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing()) Updater.autoCheck(this);
            }, 8000);
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("asked_location", false)) {
            prefs.edit().putBoolean("asked_location", true).apply();
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS);
            showSettings();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applySystemBars(this);
        // O rádio esteve a dormir (carro desligado) mais de 5 min? Então o carro acabou de ligar.
        long heartbeat = prefs.getLong("heartbeat", 0);
        if (heartbeat > 0 && System.currentTimeMillis() - heartbeat > 5 * 60 * 1000L
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putLong("heartbeat", System.currentTimeMillis()).apply();
            carStarted();
        }
        TripService.start(this);
        TripService.addListener(this);
        ObdService.start(this);
        Obd.addListener(this);
        Explore.addListener(this);
        renderTrip();
        maybeShowEngineAlert();
        startLocation();
        hub.addListener(this);
        RadioService.addListener(this);
        hub.start();
        hub.retryArtIfMissing();
        renderMedia();
        weatherHandler.post(weatherTick);
        weatherHandler.post(nightTick);
        if (fxView != null) fxView.setRunning(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocation();
        hub.removeListener(this);
        RadioService.removeListener(this);
        TripService.removeListener(this);
        Obd.removeListener(this);
        Explore.removeListener(this);
        weatherHandler.removeCallbacks(weatherTick);
        weatherHandler.removeCallbacks(nightTick);
        if (fxView != null) fxView.setRunning(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.applySystemBars(this);
    }

    @Override
    public void onMediaChanged() {
        renderMedia();
    }

    /** Atualiza o cartão de multimédia do ecrã inicial. */
    /** Abre o ecrã certo: Rádio se a rádio online está ligada, senão "A tocar" (telemóvel). */
    private void openMediaScreen() {
        startActivity(new Intent(this, RadioService.isOn() ? RadioNowActivity.class : NowPlayingActivity.class));
    }

    @Override
    public void onRadioChanged() {
        renderMedia();
    }

    private void renderMedia() {
        TextView label = findViewById(R.id.media_label);
        TextView title = findViewById(R.id.media_title);
        TextView artist = findViewById(R.id.media_artist); // só existe no layout Painel
        ImageView play = findViewById(R.id.btn_play);
        if (label == null || title == null) return;

        if (RadioService.isOn() && RadioService.currentIndex() >= 0) {
            RadioStations.Station st = RadioStations.ALL[RadioService.currentIndex()];
            boolean song = RadioService.state() == RadioService.State.PLAYING
                    && RadioMeta.hasSong(RadioService.currentIndex());
            label.setText(RadioService.state() == RadioService.State.LOADING ? "RÁDIO · A LIGAR"
                    : "RÁDIO · " + st.name.toUpperCase(java.util.Locale.ROOT));
            title.setText(song ? RadioMeta.title : st.name);
            if (artist != null) {
                artist.setText(song ? (RadioMeta.artist == null ? st.name : RadioMeta.artist) : st.genre);
            }
            play.setImageResource(R.drawable.ic_pause_dark);
            ImageView art = findViewById(R.id.media_art);
            art.setImageBitmap(song && RadioMeta.art != null ? RadioMeta.art
                    : RadioStations.art(this, RadioService.currentIndex()));
            art.setVisibility(View.VISIBLE);
            findViewById(R.id.media_art_placeholder).setVisibility(View.GONE);
            return;
        }

        if (hub.hasMedia()) {
            label.setText(hub.isPlaying() ? "A TOCAR" : "EM PAUSA");
            title.setText(hub.title());
            if (artist != null) artist.setText(hub.artist() != null ? hub.artist() : " ");
        } else {
            label.setText("MULTIMÉDIA");
            title.setText("Música");
            if (artist != null) {
                artist.setText(hub.hasAccess() ? "Nada a tocar" : "Toque para ativar");
            }
        }
        play.setImageResource(hub.isPlaying() ? R.drawable.ic_pause_dark : R.drawable.ic_play_dark);

        // Capa pequena
        ImageView art = findViewById(R.id.media_art);
        View artPlaceholder = findViewById(R.id.media_art_placeholder);
        android.graphics.Bitmap cover = hub.currentArt();
        if (cover != null) {
            art.setImageBitmap(cover);
            art.setVisibility(View.VISIBLE);
            artPlaceholder.setVisibility(View.GONE);
        } else {
            art.setImageDrawable(null);
            art.setVisibility(View.GONE);
            artPlaceholder.setVisibility(View.VISIBLE);
        }

        // Cor de destaque a acompanhar a capa
        int next = accent();
        if (rootView != null && next != currentAccent) {
            Ui.animateAccent(rootView, currentAccent, next);
            currentAccent = next;
        }
    }

    @Override
    public void onBackPressed() {
        // No ecrã inicial, o botão "voltar" não faz nada.
    }

    // ---------- Interface ----------

    private int accent() {
        return Ui.effectiveAccent(this, hub);
    }

    private boolean isFotoLayout() {
        return "foto".equals(prefs.getString("layout", "painel"));
    }

    private void buildUi() {
        ScaleFrame frame = new ScaleFrame(this);
        int layout = isFotoLayout() ? R.layout.layout_foto : R.layout.layout_painel;
        getLayoutInflater().inflate(layout, frame, true);
        setContentView(frame);
        rootView = frame;
        currentAccent = accent();
        Ui.style(frame, currentAccent);
        View artBox = findViewById(R.id.media_art_box);
        artBox.setClipToOutline(true);
        if (artBox.isClickable()) {
            artBox.setOnClickListener(v -> openMediaScreen());
        }

        speedView = findViewById(R.id.speed);

        View photoArea = findViewById(R.id.photo_area);
        photoArea.setClipToOutline(true);
        photoArea.setOnLongClickListener(v -> {
            pickPhoto();
            return true;
        });

        View clock = findViewById(R.id.clock_card);
        clock.setOnLongClickListener(v -> {
            showSettings();
            return true;
        });

        for (int i = 0; i < SLOTS.length; i++) {
            final int slot = i;
            View button = findViewById(SLOT_IDS[i]);
            if (SLOTS[i].equals("music")) {
                // O botão Música abre o nosso ecrã "A tocar"
                button.setOnClickListener(v -> startActivity(new Intent(this, NowPlayingActivity.class)));
            } else if (SLOTS[i].equals("radio")) {
                // O botão Rádio abre o ecrã de rádio online (o FM do carro está lá dentro)
                button.setOnClickListener(v -> startActivity(new Intent(this, RadioActivity.class)));
            } else {
                button.setOnClickListener(v -> openSlot(slot));
            }
            button.setOnLongClickListener(v -> {
                chooseAppForSlot(slot);
                return true;
            });
        }

        View apps = findViewById(R.id.dock_apps);
        apps.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        apps.setOnLongClickListener(v -> {
            showSettings();
            return true;
        });

        // Com a rádio online ligada, os botões controlam a rádio; senão, a música do telemóvel
        findViewById(R.id.btn_prev).setOnClickListener(v -> {
            if (RadioService.isOn()) RadioService.send(this, RadioService.ACTION_PREV, -1);
            else hub.previous();
        });
        findViewById(R.id.btn_play).setOnClickListener(v -> {
            if (RadioService.isOn()) RadioService.send(this, RadioService.ACTION_STOP, -1);
            else hub.playPause();
        });
        findViewById(R.id.btn_next).setOnClickListener(v -> {
            if (RadioService.isOn()) RadioService.send(this, RadioService.ACTION_NEXT, -1);
            else hub.next();
        });
        View mediaCard = findViewById(R.id.media_card);
        mediaCard.setOnClickListener(v -> openMediaScreen());
        mediaCard.setOnLongClickListener(v -> {
            openSlot(1); // abre a app de música escolhida
            return true;
        });

        // Botão Carro: viagens e dados do carro
        findViewById(R.id.dock_car).setOnClickListener(v -> startActivity(new Intent(this, TripsActivity.class)));
        showPhoto();
        renderMedia();
        renderWeather();
        renderTrip();
        applyNight();
        if (listening && speedView != null) speedView.setText("0");
    }

    // ---------- Botões da barra ----------

    private void openSlot(int slot) {
        String pkg = prefs.getString("slot_" + SLOTS[slot] + "_pkg", null);
        String cls = prefs.getString("slot_" + SLOTS[slot] + "_cls", null);
        if (!AppUtils.launch(this, pkg, cls)) {
            Toast.makeText(this, "Escolha a app para o botão " + SLOT_NAMES[slot], Toast.LENGTH_SHORT).show();
            chooseAppForSlot(slot);
        }
    }

    private void chooseAppForSlot(int slot) {
        List<AppUtils.AppEntry> apps = AppUtils.listApps(this, true);
        Sheet sheet = Sheet.create(this, "Que app abre o botão " + SLOT_NAMES[slot] + "?");
        for (AppUtils.AppEntry app : apps) {
            sheet.item(app.icon, app.label, () -> {
                prefs.edit()
                        .putString("slot_" + SLOTS[slot] + "_pkg", app.pkg)
                        .putString("slot_" + SLOTS[slot] + "_cls", app.cls)
                        .apply();
                Toast.makeText(this, SLOT_NAMES[slot] + " → " + app.label, Toast.LENGTH_SHORT).show();
            });
        }
        sheet.button("Cancelar", false, null).show();
    }

    /** Escolhe sozinho a app de cada botão pelo nome. force = substitui as escolhas antigas. */
    private void detectApps(boolean force) {
        List<AppUtils.AppEntry> apps = AppUtils.listApps(this, false);
        SharedPreferences.Editor editor = prefs.edit();
        int found = 0;
        for (int i = 0; i < SLOTS.length; i++) {
            String key = "slot_" + SLOTS[i] + "_pkg";
            if (!force && prefs.getString(key, null) != null) continue;
            AppUtils.AppEntry app = AppUtils.detect(apps, SLOT_KEYWORDS[i]);
            if (app != null) {
                editor.putString(key, app.pkg).putString("slot_" + SLOTS[i] + "_cls", app.cls);
                found++;
            } else if (force) {
                editor.remove(key).remove("slot_" + SLOTS[i] + "_cls");
            }
        }
        editor.putBoolean("detected", true).apply();
        if (force) {
            Toast.makeText(this, "Encontrei " + found + " de " + SLOTS.length + " apps. "
                    + "Toque longo num botão para mudar.", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Definições ----------

    private void showSettings() {
        Sheet.create(this, "Definições do launcher")
                .width(720)
                .section("Aparência")
                .item("Escolher foto do carro", this::pickPhoto)
                .item("Repor a foto original", () -> {
                    //noinspection ResultOfMethodCallIgnored
                    new File(getFilesDir(), PHOTO_FILE).delete();
                    showPhoto();
                })
                .item("Layout", () -> isFotoLayout() ? "Foto em destaque" : "Painel", false, () -> {
                    prefs.edit().putString("layout", isFotoLayout() ? "painel" : "foto").apply();
                    buildUi();
                })
                .item("Cor de destaque", () -> ACCENT_NAMES[accentIndex()], true, this::chooseAccent)
                .item("Cor a acompanhar a capa", () -> onOff("dynamic_color", true), false, () -> {
                    toggle("dynamic_color", true);
                    buildUi();
                })
                .item("Modo noite", this::nightLabel, true, this::chooseNightMode)
                .item("Efeitos do tempo na foto", () -> onOff("weather_fx", true), false, () -> {
                    toggle("weather_fx", true);
                    updateFx();
                })
                .item("Experimentar efeitos", this::tryFx)
                .item("Barra de cima do sistema", () -> prefs.getBoolean("show_bars", false) ? "Visível" : "Escondida",
                        false, () -> {
                            toggle("show_bars", false);
                            Ui.applySystemBars(this);
                        })
                .section("Ecrã inicial")
                .item("Tempo", () -> onOff("show_weather", true), false, () -> {
                    toggle("show_weather", true);
                    maybeUpdateWeather();
                    renderWeather();
                })
                .item("Nome na saudação", () -> {
                    String n = prefs.getString("driver_name", "Dinis").trim();
                    return n.isEmpty() ? "Sem nome" : n;
                }, true, this::editDriverName)
                .item("Animação de arranque", () -> onOff("show_splash", true), false,
                        () -> toggle("show_splash", true))
                .item("Som de arranque", () -> StartSound.name(StartSound.choice(this)), true, this::chooseStartSound)
                .item("Ver a animação de arranque agora", () -> {
                    StartSound.play(this);
                    Splash.show(this, carBitmap);
                    Night.apply(this, Night.isNight(this));
                })
                .item("Detetar outra vez as apps dos botões", () -> detectApps(true))
                .item("Mostrar apps escondidas", () -> {
                    prefs.edit().remove("hidden_apps").apply();
                    Toast.makeText(this, "Todas as apps voltaram à lista", Toast.LENGTH_SHORT).show();
                })
                .section("Música")
                .item("Acesso a notificações", () -> hub.hasAccess() ? "Ativo" : "Desativado", true,
                        () -> MediaHub.openAccessSettings(this))
                .section("Carro")
                .item("Adaptador OBD2 (motor)", () -> Obd.savedMac(this) == null ? "Por ligar" : Obd.savedName(this),
                        true, () -> startActivity(new Intent(this, EngineActivity.class)))
                .item("Guardar viagens a partir de", () -> TripsActivity.minLabel(TripService.minKm(this)), true,
                        () -> TripsActivity.chooseMinKm(this, this::renderTrip))
                .item("App Golf no iPhone", this::showPhoneApp)
                .item("Ligar o rádio ao PC (ficheiros)", this::shareCarApps)
                .section("Sistema")
                .item("Procurar atualizações", Updater::currentVersion, true, () -> Updater.check(this, false))
                .item("Definições do Android", () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "Não foi possível abrir as definições", Toast.LENGTH_SHORT).show();
                    }
                })
                .button("Fechar", true, null)
                .show();
    }

    /** Código QR para abrir a app Golf (página) no iPhone. */
    private void showPhoneApp() {
        if (!CloudSync.configured()) {
            Sheet.message(this, "App Golf no iPhone",
                    "Ainda falta ligar a base de dados (o passo do Firebase). Depois disso, numa atualização, "
                            + "aparece aqui o código QR.");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(CloudSync.qr(this, 8));
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(qr, new LinearLayout.LayoutParams(300, 300));
        box.setPadding(0, 6, 0, 6);
        Sheet.create(this, "App Golf no iPhone").width(860)
                .message("1) Aponte a câmara do iPhone a este código e toque no link.\n"
                        + "2) No Safari: botão Partilhar → \"Adicionar ao ecrã principal\".\n"
                        + "O link é secreto: quem o tiver vê onde está o carro.")
                .view(box)
                .button("OK", true, null)
                .show();
        CloudSync.status(this, null, true);
        CloudSync.trips(this);
        CloudSync.explore(this);
    }

    /** Mostra um endereço para descarregar as apps do carro no PC (mesma rede Wi-Fi). Só lê. */
    private void shareCarApps() {
        ApkShare.onReceived = what -> {
            if (what.equals("photo")) showPhoto();
            Toast.makeText(this, what.equals("photo") ? "Foto nova recebida do PC" : "Som de arranque recebido do PC",
                    Toast.LENGTH_LONG).show();
        };
        String url = ApkShare.start(this);
        if (url == null) {
            Sheet.message(this, "Ligar o rádio ao PC",
                    "O rádio não está ligado a nenhuma rede Wi-Fi. Ligue o hotspot do telemóvel e tente outra vez.");
            return;
        }
        Sheet.create(this, "Ligar o rádio ao PC").width(860)
                .message("1) Ligue o PC (ou o telemóvel) à MESMA rede Wi-Fi do rádio (o hotspot do telemóvel).\n\n"
                        + "2) No browser, escreva este endereço:\n\n      " + url + "\n\n"
                        + "3) Na página pode enviar um som de arranque ou uma foto do carro para o rádio, "
                        + "e descarregar ficheiros para o Claude analisar.\n\n"
                        + "Não muda nada no carro. Desliga-se sozinho daqui a 15 minutos.")
                .button("Parar", false, ApkShare::stop)
                .button("OK", true, null)
                .show();
    }

    /** "Boa noite, Dinis": muda (ou apaga) o nome da animação de arranque. */
    private void editDriverName() {
        Sheet[] holder = new Sheet[1];
        holder[0] = Sheet.create(this, "Nome na saudação").width(700)
                .message("Aparece na animação de arranque: \"Boa noite, Dinis\". Deixe vazio para não mostrar nome.")
                .input(prefs.getString("driver_name", "Dinis"))
                .button("Cancelar", false, null)
                .button("Guardar", true, () ->
                        prefs.edit().putString("driver_name", holder[0].inputValue()).apply());
        holder[0].show();
    }

    @Override
    public void onExploreChanged() {
    }

    @Override
    public void onNewConcelho(String name, String district, int count) {
        Celebrate.show(this, name, count + " de " + Concelhos.TOTAL + " concelhos  ·  " + district,
                Ui.effectiveAccent(this, hub));
    }

    @Override
    public void onObdChanged() {
        maybeShowEngineAlert();
    }

    private boolean engineAlertOpen;

    /** A luz do motor acendeu (ou há erros novos): avisa com uma janela no ecrã inicial. */
    private void maybeShowEngineAlert() {
        if (!Obd.alertPending || engineAlertOpen || isFinishing()) return;
        engineAlertOpen = true;
        StringBuilder text = new StringBuilder();
        for (String c : Obd.codes) {
            Dtc.Info info = Dtc.info(c);
            text.append(c).append(" · ").append(info.text).append('\n').append(info.advice()).append("\n\n");
        }
        if (text.length() == 0) text.append("O carro acendeu a luz do motor, mas ainda não enviou o código.");
        Sheet.create(this, Obd.mil ? "⚠ Luz do motor acesa" : "⚠ Aviso do motor").width(860)
                .message(text.toString().trim())
                .button("OK", false, () -> {
                    engineAlertOpen = false;
                    Obd.acknowledge(this);
                })
                .button("Ver detalhes", true, () -> {
                    engineAlertOpen = false;
                    Obd.acknowledge(this);
                    startActivity(new Intent(this, EngineActivity.class));
                })
                .show();
    }

    private String onOff(String key, boolean def) {
        return prefs.getBoolean(key, def) ? "Ligado" : "Desligado";
    }

    private void toggle(String key, boolean def) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply();
    }

    private int accentIndex() {
        int chosen = prefs.getInt("accent", ACCENTS[0]);
        for (int i = 0; i < ACCENTS.length; i++) if (ACCENTS[i] == chosen) return i;
        return 0;
    }

    private String nightLabel() {
        String mode = Night.mode(this);
        return Night.ON.equals(mode) ? "Sempre ligado" : Night.OFF.equals(mode) ? "Sempre desligado" : "Automático";
    }


    private void chooseAccent() {
        Sheet sheet = Sheet.create(this, "Cor de destaque")
                .message("Usada quando a música não tem capa, ou com a cor da capa desligada.");
        for (int i = 0; i < ACCENTS.length; i++) {
            final int index = i;
            sheet.item(ACCENT_NAMES[i], () -> index == accentIndex() ? "Em uso" : null, true, () -> {
                prefs.edit().putInt("accent", ACCENTS[index]).apply();
                buildUi();
            });
        }
        sheet.button("Cancelar", false, null).show();
    }

    // ---------- Som de arranque ----------

    private void chooseStartSound() {
        Sheet sheet = Sheet.create(this, "Som de arranque")
                .message("Toca quando o carro liga, com a animação. Toque num som para o ouvir e escolher.");
        for (int i = 0; i < StartSound.IDS.length; i++) {
            final String id = StartSound.IDS[i];
            if (id.equals("custom")) {
                boolean has = new File(getFilesDir(), StartSound.CUSTOM_FILE).exists();
                sheet.item(has ? "O meu som" : "O meu som (escolher ficheiro…)",
                        () -> id.equals(StartSound.choice(this)) ? "Em uso" : (has ? "Trocar…" : null), false, () -> {
                            if (has && !id.equals(StartSound.choice(this))) {
                                StartSound.setChoice(this, id);
                                StartSound.play(this, id);
                            } else {
                                pickStartSound();
                            }
                        });
            } else {
                sheet.item(StartSound.NAMES[i], () -> id.equals(StartSound.choice(this)) ? "Em uso" : null, false, () -> {
                    StartSound.setChoice(this, id);
                    StartSound.play(this, id);
                });
            }
        }
        sheet.button("OK", true, null).show();
    }

    private void pickStartSound() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Escolher o som de arranque"), REQ_SOUND);
        } catch (Exception e) {
            Toast.makeText(this, "Não encontrei nenhuma app de ficheiros", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Foto do carro ----------

    private void pickPhoto() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Escolher foto do carro"), REQ_PHOTO);
        } catch (Exception e) {
            Toast.makeText(this, "Não encontrei nenhuma app de galeria ou ficheiros", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SOUND && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri sound = data.getData();
            new Thread(() -> {
                boolean ok = copyToFile(sound, new File(getFilesDir(), StartSound.CUSTOM_FILE));
                runOnUiThread(() -> {
                    if (ok) {
                        StartSound.setChoice(this, "custom");
                        StartSound.play(this, "custom");
                    } else {
                        Toast.makeText(this, "Não consegui ler esse som", Toast.LENGTH_LONG).show();
                    }
                });
            }).start();
            return;
        }
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            boolean ok = copyToFile(uri, new File(getFilesDir(), PHOTO_FILE));
            runOnUiThread(() -> {
                if (!ok) {
                    Toast.makeText(this, "Não consegui ler essa foto", Toast.LENGTH_LONG).show();
                }
                showPhoto();
            });
        }).start();
    }

    private boolean copyToFile(Uri uri, File target) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) return false;
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private WeatherFxView fxView;
    private WeatherFxView.Fx demoFx;
    private boolean demoNight;
    private long demoUntil;

    /** Chuva, neve, sol, noite… por cima da foto, conforme o tempo lá fora. */
    private void updateFx() {
        if (fxView == null) return;
        long now = System.currentTimeMillis();
        if (now < demoUntil) {
            fxView.set(demoFx, demoNight);
            return;
        }
        if (!prefs.getBoolean("weather_fx", true)) {
            fxView.set(WeatherFxView.Fx.NONE, false);
            return;
        }
        WeatherFxView.Fx e = WeatherFxView.Fx.NONE;
        if (now - prefs.getLong("w_time", 0) < Weather.MAX_AGE_MS) {
            e = WeatherFxView.fromCode(prefs.getInt("w_code", -1));
        }
        fxView.set(e, Night.isNight(this));
    }

    private void tryFx() {
        String[] labels = {"Sol", "Nublado", "Chuviscos", "Chuva", "Trovoada", "Neve", "Nevoeiro",
                "Noite limpa", "Noite com chuva"};
        WeatherFxView.Fx[] effects = {WeatherFxView.Fx.SUN, WeatherFxView.Fx.CLOUDS, WeatherFxView.Fx.DRIZZLE,
                WeatherFxView.Fx.RAIN, WeatherFxView.Fx.STORM, WeatherFxView.Fx.SNOW, WeatherFxView.Fx.FOG,
                WeatherFxView.Fx.SUN, WeatherFxView.Fx.RAIN};
        Sheet sheet = Sheet.create(this, "Experimentar efeitos (20 segundos)");
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            sheet.item(labels[i], () -> {
                demoFx = effects[k];
                demoNight = k >= 7;
                demoUntil = System.currentTimeMillis() + 20000;
                updateFx();
                weatherHandler.postDelayed(this::updateFx, 20500);
            });
        }
        sheet.button("Fechar", true, null).show();
    }

    private void showPhoto() {
        ImageView photo = findViewById(R.id.car_photo);
        if (photo != null && photo.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) photo.getParent();
            if (fxView == null || fxView.getParent() != parent) {
                if (fxView != null && fxView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) fxView.getParent()).removeView(fxView);
                }
                fxView = new WeatherFxView(this);
                parent.addView(fxView, parent.indexOfChild(photo) + 1, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fxView.setRunning(true);
            }
        }
        View placeholder = findViewById(R.id.photo_placeholder);
        View scrim = findViewById(R.id.scrim); // só existe no layout Foto
        File file = new File(getFilesDir(), PHOTO_FILE);
        Bitmap bitmap = file.exists() ? decodePhoto(file) : null;
        if (bitmap == null && !file.exists()) {
            // Sem foto escolhida: usa a foto que vem dentro da app
            try {
                bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.car_default);
            } catch (OutOfMemoryError ignored) {
                bitmap = null;
            }
        }

        carBitmap = bitmap;
        if (fxView != null) fxView.setVisibility(bitmap != null ? View.VISIBLE : View.GONE);
        updateFx();
        if (bitmap != null) {
            photo.setImageBitmap(bitmap);
            photo.setVisibility(View.VISIBLE);
            placeholder.setVisibility(View.GONE);
            if (scrim != null) scrim.setVisibility(View.VISIBLE);
        } else {
            if (file.exists()) {
                Toast.makeText(this, "Formato de foto não suportado. Use JPG ou PNG.", Toast.LENGTH_LONG).show();
            }
            photo.setImageDrawable(null);
            photo.setVisibility(View.GONE);
            placeholder.setVisibility(View.VISIBLE);
            if (scrim != null) scrim.setVisibility(View.GONE);
        }
    }

    /** Lê a foto reduzida (para não gastar memória) e endireita-a segundo o EXIF. */
    private Bitmap decodePhoto(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) return null;

            int rotation = 0;
            try {
                ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            } catch (Exception ignored) {
                // sem EXIF: fica como está
            }
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            return bitmap;
        } catch (OutOfMemoryError | Exception e) {
            return null;
        }
    }

    // ---------- Viagem ----------

    @Override
    public void onTripChanged() {
        renderTrip();
    }

    private void renderTrip() {
        // A viagem já não aparece no ecrã inicial: está no ecrã Carro (botão Carro).
    }

    // ---------- Animação de arranque ----------

    /** O carro acabou de ligar: som de arranque + animação. */
    private void carStarted() {
        StartSound.play(this);
        showSplashIfEnabled();
    }

    private void showSplashIfEnabled() {
        if (!prefs.getBoolean("show_splash", true)) return;
        Splash.show(this, carBitmap);
        Night.apply(this, Night.isNight(this)); // o véu do modo noite fica por cima
    }

    // ---------- Modo noite ----------

    private void applyNight() {
        boolean night = Night.isNight(this);
        Night.apply(this, night);
        updateFx();
        ImageView photo = findViewById(R.id.car_photo);
        if (photo != null) {
            // A foto escurece um pouco mais do que o resto
            if (night) photo.setColorFilter(0x59000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
            else photo.clearColorFilter();
        }
    }

    private void chooseNightMode() {
        final String[] modes = {Night.AUTO, Night.ON, Night.OFF};
        String[] labels = {"Automático (do pôr ao nascer do sol)", "Sempre ligado", "Sempre desligado"};
        Sheet sheet = Sheet.create(this, "Modo noite");
        for (int i = 0; i < modes.length; i++) {
            final String mode = modes[i];
            sheet.item(labels[i], () -> mode.equals(Night.mode(this)) ? "Em uso" : null, true, () -> {
                Night.setMode(this, mode);
                applyNight();
            });
        }
        sheet.button("Cancelar", false, null).show();
    }

    // ---------- Tempo ----------

    private void renderWeather() {
        View row = findViewById(R.id.weather_row);
        if (row == null) return;
        long age = System.currentTimeMillis() - prefs.getLong("w_time", 0);
        if (!prefs.getBoolean("show_weather", true) || age > Weather.MAX_AGE_MS) {
            row.setVisibility(View.GONE);
            return;
        }
        ImageView icon = findViewById(R.id.weather_icon);
        TextView temp = findViewById(R.id.weather_temp);
        icon.setImageResource(Weather.iconFor(prefs.getInt("w_code", 0), prefs.getBoolean("w_day", true)));
        updateFx();
        temp.setText(Math.round(prefs.getFloat("w_temp", 0f)) + "°");
        row.setVisibility(View.VISIBLE);
    }

    /** Vai buscar o tempo se o último tiver mais de 30 min (e não tentou há menos de 5). */
    private void maybeUpdateWeather() {
        if (!prefs.getBoolean("show_weather", true)) return;
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("w_time", 0) < Weather.REFRESH_MS) return;
        if (now - lastWeatherAttempt < 5 * 60 * 1000L) return;
        Location location = bestLocation();
        if (location == null) return; // tenta outra vez quando o GPS apanhar sinal
        lastWeatherAttempt = now;
        Weather.fetch(this, location.getLatitude(), location.getLongitude(), ok -> renderWeather());
    }

    private Location bestLocation() {
        if (lastFix != null) return lastFix;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        Location best = null;
        try {
            for (String provider : locationManager.getProviders(true)) {
                Location l = locationManager.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    // ---------- Velocidade (GPS) ----------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            startLocation();
            TripService.start(this);
        }
    }

    private void startLocation() {
        if (listening || locationManager == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (speedView != null) speedView.setText("--");
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener);
            listening = true;
            if (speedView != null) speedView.setText("0");
        } catch (Exception e) {
            // rádio sem GPS
            if (speedView != null) speedView.setText("--");
        }
    }

    private void stopLocation() {
        if (!listening) return;
        try {
            locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) {
        }
        listening = false;
    }
}
