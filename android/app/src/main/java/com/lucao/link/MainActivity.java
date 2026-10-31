package com.lucao.link;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.SizeF;
import android.view.ScaleGestureDetector;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Apenas UI.
 * O pipeline de video (camera -> H264 -> TCP) pertence
 * exclusivamente ao CameraForegroundService.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LUCAO_UI";

    private TextView statusText;
    private TextView connectionBadge;

    private Button connectButton;

    private PreviewView cameraPreview;

    private TextView zoomBadge;

    private android.widget.CheckBox webcamSwitch;

    private android.widget.CheckBox microphoneSwitch;

    private Button rotateButton;
    private Button mirrorButton;

    // Controles sobrepostos ao video (estilo app de camera).
    private Button flashButton;
    private Button lenteButton;
    private Button autoRotButton;
    private LinearLayout zoomPresets;
    private android.view.View bolinhaView;

    // Ultimo estado pintado, para nao repintar a cada status
    // (o status chega varias vezes por segundo e repintar tudo
    // derruba o aparelho por memoria/CPU).
    private float ultimoPresetRatio = -1f;
    private float ultimoZoomBadge = -1f;
    private boolean ultimaConexao = false;
    private boolean ultimoFlash = false;
    private boolean ultimoFlashDisp = false;
    private String ultimaLente = "";
    private boolean ultimoAutoRot = true;

    // Zoom corrente em RATIO (1.0 = 1x ... zoomMaxLocal = max).
    // Fonte da verdade local para pinça e presets.
    private float zoomRatioLocal = 1.0f;

    // Estado local otimista (o servico confirma via broadcast).
    private int rotacaoLocal = 0;
    private boolean espelhoLocal = false;
    private boolean micLocal = true;
    private boolean flashLocal = false;
    private boolean flashDisponivelLocal = false;
    private boolean autoRotLocal = true;

    // Lente e faixa de zoom reportadas pelo servico.
    private String lenteLocal = "back";
    private List<String> lentesDisponiveisLocal = new ArrayList<>();
    private float zoomMinLocal = 1f;
    private float zoomMaxLocal = 1f;

    // Guarda os presets ja desenhados, para nao recriar a cada
    // status (o status chega varias vezes por segundo).
    private String presetsAssinatura = "";

    // Evita loop ao marcar/desmarcar o toggle via codigo.
    private boolean sincronizandoMic = false;

    private EditText pcHostInput;

    private boolean sincronizandoWebcam = false;

    // Pinça: enquanto os dedos estao na tela, o status vindo do
    // servico nao sobrescreve a escala que o usuario escolheu.
    private boolean pinchAtivo = false;

    // Debounce do zoom: agrupa varias mudancas da pinca num unico
    // comando ao servico, para nao inundar o CameraX.
    private final android.os.Handler zoomHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable zoomEnvioPendente = null;
    private float zoomLinearPendente = 0.0f;

    private ScaleGestureDetector scaleDetector;

    private static final String PREFS = "lucao_link";
    private static final String PREF_PC_HOST = "pcHost";

    // ============================================================
    // STATUS VINDO DO SERVICO (conexao TCP + zoom real)
    // ============================================================

    private final BroadcastReceiver statusReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent
                ) {

                    boolean conectado =
                            intent.getBooleanExtra(
                                    "conectado",
                                    false
                            );

                    if (conectado) {

                        connectionBadge.setText(
                                "● STREAM ATIVO"
                        );

                        connectionBadge.setTextColor(
                                ContextCompat.getColor(
                                        context,
                                        R.color.term_success
                                )
                        );

                    } else {

                        connectionBadge.setText(
                                "● SEM STREAM"
                        );

                        connectionBadge.setTextColor(
                                ContextCompat.getColor(
                                        context,
                                        R.color.term_danger
                                )
                        );
                    }

                    pintarBolinha(conectado);

                    // Sincroniza o checkbox quando o comando veio
                    // do painel do PC.
                    if (intent.hasExtra("webcam") && webcamSwitch != null) {

                        boolean ligada =
                                intent.getBooleanExtra("webcam", true);

                        if (webcamSwitch.isChecked() != ligada) {

                            sincronizandoWebcam = true;
                            webcamSwitch.setChecked(ligada);
                            sincronizandoWebcam = false;
                        }
                    }

                    if (intent.hasExtra("zoom")) {

                        float zoom =
                                intent.getFloatExtra(
                                        "zoom",
                                        1.0f
                                );

                        float minimo =
                                intent.getFloatExtra(
                                        "zoomMin",
                                        1.0f
                                );

                        float maximo =
                                intent.getFloatExtra(
                                        "zoomMax",
                                        1.0f
                                );

                        zoomMinLocal = minimo;
                        zoomMaxLocal = maximo;

                        desenharZoomPresets();

                        // Enquanto a pinça esta ativa, o status nao
                        // sobrescreve o que o dedo escolheu (o sensor
                        // reporta em degraus e faria o zoom "saltar").
                        if (!pinchAtivo) {

                            zoomRatioLocal = Math.max(
                                    1.0f,
                                    Math.min(maximo, zoom)
                            );

                            atualizarZoomBadge(zoomRatioLocal);

                            marcarPresetAtivo(
                                    arredondarPreset(zoomRatioLocal)
                            );
                        }
                    }

                    // Sincroniza lente/flash/auto-rotacao quando o
                    // comando veio do painel do PC.
                    if (intent.hasExtra("lentesDisponiveis")) {

                        ArrayList<String> lentes =
                                intent.getStringArrayListExtra(
                                        "lentesDisponiveis"
                                );

                        if (lentes != null) {
                            lentesDisponiveisLocal = lentes;
                        }
                    }

                    if (intent.hasExtra("lente")) {

                        lenteLocal = intent.getStringExtra("lente");

                        pintarLente();

                        // Faixa de zoom muda com a lente: forca
                        // reconstrucao dos presets.
                        presetsAssinatura = "";

                        desenharZoomPresets();
                    }

                    if (intent.hasExtra("flash")) {

                        flashLocal =
                                intent.getBooleanExtra(
                                        "flash",
                                        false
                                );

                        flashDisponivelLocal =
                                intent.getBooleanExtra(
                                        "flashDisponivel",
                                        true
                                );

                        pintarFlash();
                    }

                    if (intent.hasExtra("autoRotacao")) {

                        autoRotLocal =
                                intent.getBooleanExtra(
                                        "autoRotacao",
                                        true
                                );

                        pintarAutoRot();
                    }

                    // Sincroniza os botoes de orientacao quando o
                    // comando partiu do painel do PC.
                    if (intent.hasExtra("rotacao") &&
                            rotateButton != null) {

                        int rotacao =
                                intent.getIntExtra(
                                        "rotacao",
                                        0
                                );

                        rotacaoLocal = rotacao;

                        rotateButton.setText(
                                "girar " + rotacao + "°"
                        );
                    }

                    if (intent.hasExtra("espelho") &&
                            mirrorButton != null) {

                        boolean espelhado =
                                intent.getBooleanExtra(
                                        "espelho",
                                        false
                                );

                        espelhoLocal = espelhado;

                        // "term_toggle"-like: muda o texto do botao.
                        mirrorButton.setText(
                                espelhado
                                        ? "espelho ON"
                                        : "espelho OFF"
                        );
                    }

                    // Sincroniza o toggle do microfone (pode ter
                    // sido ligado/desligado pelo painel do PC).
                    if (intent.hasExtra("mic") &&
                            microphoneSwitch != null) {

                        boolean micLigado =
                                intent.getBooleanExtra(
                                        "mic",
                                        true
                                );

                        micLocal = micLigado;

                        sincronizandoMic = true;
                        microphoneSwitch.setChecked(micLigado);
                        sincronizandoMic = false;
                    }
                }
            };

    // ============================================================
    // PERMISSAO DE CAMERA
    // ============================================================

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    permitido -> {

                        if (permitido) {

                            // Permissao garantida:
                            // pede ao servico para iniciar a camera.
                            enviarAcaoServico(
                                    CameraForegroundService
                                            .ACTION_START_CAMERA
                            );

                        } else {

                            statusText.setText(
                                    "CAMERA NAO AUTORIZADA"
                            );

                            statusText.setTextColor(
                                    ContextCompat.getColor(
                                            MainActivity.this,
                                            R.color.term_danger
                                    )
                            );
                        }
                    }
            );

    // ============================================================
    // PERMISSAO DE MICROFONE
    // ============================================================

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    permitido -> {

                        if (!permitido) {

                            statusText.setText(
                                    "MICROFONE NAO AUTORIZADO"
                            );

                            statusText.setTextColor(
                                    ContextCompat.getColor(
                                            MainActivity.this,
                                            R.color.term_danger
                                    )
                            );
                        }

                        // O audio sobe junto com o video dentro do
                        // servico; nada a iniciar aqui.
                    }
            );

    // ============================================================
    // LIFECYCLE
    // ============================================================

    @Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // CameraX pertence ao CameraForegroundService.
    Intent cameraServiceIntent =
            new Intent(
                    this,
                    CameraForegroundService.class
            );

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(cameraServiceIntent);
    } else {
        startService(cameraServiceIntent);
    }

        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        connectionBadge = findViewById(R.id.connectionBadge);

        connectButton = findViewById(R.id.connectButton);

        cameraPreview = findViewById(R.id.cameraPreview);

        // O pipeline pertence ao servico; a Activity so entrega
        // a superficie do PreviewView para o CameraX desenhar.
        CameraForegroundService.setPreviewProvider(
                cameraPreview.getSurfaceProvider()
        );

        // Preview preenche a tela toda (crop), sobreposto pelos
        // controles - igual a camera nativa do aparelho.
        cameraPreview.setScaleType(
                PreviewView.ScaleType.FILL_CENTER
        );

        zoomBadge = findViewById(R.id.zoomBadge);

        configurarPinca();

        webcamSwitch = findViewById(R.id.webcamSwitch);

        microphoneSwitch = findViewById(R.id.microphoneSwitch);

        rotateButton = findViewById(R.id.rotateButton);
        mirrorButton = findViewById(R.id.mirrorButton);

        flashButton = findViewById(R.id.btnFlash);
        lenteButton = findViewById(R.id.btnLente);
        autoRotButton = findViewById(R.id.btnAutoRot);
        zoomPresets = findViewById(R.id.zoomPresets);
        bolinhaView = findViewById(R.id.bolinha);

        pcHostInput = findViewById(R.id.pcHostInput);

        // ----------------------------
        // CONTROLES DE CAMERA (UI -> servico)
        // ----------------------------

        flashButton.setOnClickListener(v -> {

            flashLocal = !flashLocal;

            Bundle extras = new Bundle();
            extras.putBoolean("ligado", flashLocal);

            enviarAcaoServico(
                    CameraForegroundService.ACTION_SET_FLASH,
                    extras
            );

            pintarFlash();
        });

        lenteButton.setOnClickListener(v -> {

            // Alterna entre as lentes que o aparelho expoe.
            String alvo;

            if (lentesDisponiveisLocal.size() >= 2) {

                alvo = lenteLocal.equals("back")
                        ? "front"
                        : "back";

            } else {

                alvo = lenteLocal.equals("back")
                        ? "front"
                        : "back";
            }

            Bundle extras = new Bundle();
            extras.putString("lente", alvo);

            enviarAcaoServico(
                    CameraForegroundService.ACTION_SET_LENTE,
                    extras
            );

            lenteLocal = alvo;
            pintarLente();
        });

        autoRotButton.setOnClickListener(v -> {

            autoRotLocal = !autoRotLocal;

            Bundle extras = new Bundle();
            extras.putBoolean("ligada", autoRotLocal);

            enviarAcaoServico(
                    CameraForegroundService
                            .ACTION_SET_AUTO_ROTACAO,
                    extras
            );

            pintarAutoRot();
        });

        // ----------------------------
        // MICROFONE (UI -> servico)
        // ----------------------------

        // O layout vinha desabilitado ("em desenvolvimento"); a
        // captura agora liga/desliga de verdade, entao habilita.
        microphoneSwitch.setEnabled(true);
        microphoneSwitch.setChecked(micLocal);

        microphoneSwitch.setOnCheckedChangeListener(
                (botao, marcado) -> {

                    if (sincronizandoMic) {
                        return;
                    }

                    micLocal = marcado;

                    Bundle extras = new Bundle();

                    extras.putBoolean("ligado", marcado);

                    enviarAcaoServico(
                            CameraForegroundService
                                    .ACTION_SET_MIC,
                            extras
                    );

                    Log.i(TAG, "MIC " + (marcado ? "ON" : "OFF"));
                }
        );

        configurarHostPc();

        detectarCamerasDisponiveis();

        // ----------------------------
        // ZOOM: so pinca + presets (UI -> servico)
        // ----------------------------

        configurarPinca();

        // ----------------------------
        // WEBCAM ON/OFF (UI -> servico)
        // ----------------------------

        webcamSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {

                    if (sincronizandoWebcam) {
                        return;
                    }

                    Bundle extras = new Bundle();

                    extras.putBoolean(
                            "ativo",
                            isChecked
                    );

                    enviarAcaoServico(
                            CameraForegroundService
                                    .ACTION_SET_CAMERA_ACTIVE,
                            extras
                    );

                    Log.i(
                            TAG,
                            "WEBCAM " +
                                    (isChecked ? "ON" : "OFF")
                    );
                }
        );

        // ----------------------------
        // ORIENTACAO (UI -> servico)
        // ----------------------------

        // rotacao atual local (o servico confirma por broadcast).
        rotateButton.setOnClickListener(v -> {

            rotacaoLocal = (rotacaoLocal + 90) % 360;

            rotateButton.setText("girar " + rotacaoLocal + "°");

            Bundle extras = new Bundle();

            extras.putInt("rotacao", rotacaoLocal);

            enviarAcaoServico(
                    CameraForegroundService
                            .ACTION_SET_ROTACAO,
                    extras
            );

            Log.i(TAG, "ROTACAO " + rotacaoLocal);
        });

        mirrorButton.setOnClickListener(v -> {

            espelhoLocal = !espelhoLocal;

            mirrorButton.setText(
                    espelhoLocal ? "espelho ON" : "espelho OFF"
            );

            Bundle extras = new Bundle();

            extras.putBoolean("espelho", espelhoLocal);

            enviarAcaoServico(
                    CameraForegroundService
                            .ACTION_SET_ESPELHO,
                    extras
            );

            Log.i(TAG, "ESPELHO " + espelhoLocal);
        });

        // ----------------------------
        // STATUS (servico -> UI)
        // ----------------------------

        registrarStatusReceiver();

        enviarAcaoServico(
                CameraForegroundService
                        .ACTION_REQUEST_STATUS
        );

        // ----------------------------
        // CONEXAO HTTP DE CONTROLE
        // ----------------------------

        connectButton.setOnClickListener(
                v -> testarConexao()
        );

        connectButton.postDelayed(
                this::testarConexao,
                1000
        );

        pintarFlash();
        pintarLente();
        pintarAutoRot();

        verificarCamera();
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();

        // Evita manter uma SurfaceProvider invalida dentro do servico.
        CameraForegroundService.setPreviewProvider(null);

        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // SERVICO
    // ============================================================

    private void enviarAcaoServico(String action) {
        enviarAcaoServico(action, null);
    }

    private void enviarAcaoServico(
            String action,
            Bundle extras
    ) {

        try {

            Intent intent =
                    new Intent(
                            this,
                            CameraForegroundService.class
                    );

            intent.setAction(action);

            if (extras != null) {
                intent.putExtras(extras);
            }

            startService(intent);

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "ERRO AO ENVIAR ACAO AO SERVICO: " +
                            action,
                    e
            );
        }
    }

    // ============================================================
    // PRESETS DE ZOOM (estilo camera 0,5x / 1x / 2x / 5x)
    // ============================================================
    //
    // O servico espera o valor LINEAR normalizado 0..1 (mesma
    // regra do CameraX). A conversao ratio -> linear e:
    //
    //     linear = (1 - 1/ratio) / (1 - 1/max)
    //
    // Assim "1x" = 0 e "max" = 1, qualquer que seja a lente.

    private static float linearDeRatio(float ratio, float max) {

        if (max <= 1.0f) {
            return 0.0f;
        }

        float linear =
                (1.0f - 1.0f / ratio) / (1.0f - 1.0f / max);

        return Math.max(0.0f, Math.min(1.0f, linear));
    }

    // Aplica um zoom em RATIO (1.0 .. zoomMaxLocal): manda o
    // valor LINEAR para o servico, atualiza badge e preset ativo.
    private void aplicarZoomRatio(float ratio) {

        float max = Math.max(1.0f, zoomMaxLocal);

        float limitado = Math.max(1.0f, Math.min(max, ratio));

        zoomRatioLocal = limitado;

        float linear = linearDeRatio(limitado, max);

        Bundle extras = new Bundle();
        extras.putFloat("zoom", linear);

        enviarAcaoServico(
                CameraForegroundService.ACTION_SET_ZOOM,
                extras
        );

        atualizarZoomBadge(limitado);
        marcarPresetAtivo(limitado);
    }

    private void irParaZoom(float ratio) {
        aplicarZoomRatio(ratio);
    }

    // Atualiza o chip "X.Xx" so quando muda de fato (o status
    // chega varias vezes por segundo).
    private void atualizarZoomBadge(float ratio) {

        if (zoomBadge == null) {
            return;
        }

        if (Math.abs(ratio - ultimoZoomBadge) < 0.05f) {
            return;
        }

        ultimoZoomBadge = ratio;

        zoomBadge.setText(
                String.format(Locale.US, "%.1fx", ratio)
        );
    }

    // ============================================================
    // PINCA PARA ZOOM (dois dedos sobre o preview)
    // ============================================================

    private void configurarPinca() {

        if (cameraPreview == null) {
            return;
        }

        // CameraX ja controla o toque de foco; a pinça roda em
        // paralelo e so consome o gesto de escala.
        cameraPreview.setClickable(true);

        scaleDetector = new ScaleGestureDetector(
                this,
                new ScaleGestureDetector
                        .SimpleOnScaleGestureListener() {

                    @Override
                    public boolean onScaleBegin(
                            ScaleGestureDetector d
                    ) {

                        pinchAtivo = true;
                        return true;
                    }

                    @Override
                    public boolean onScale(
                            ScaleGestureDetector d
                    ) {

                        float max = Math.max(
                                1.0f,
                                zoomMaxLocal
                        );

                        float novo = zoomRatioLocal
                                * d.getScaleFactor();

                        novo = Math.max(1.0f, Math.min(max, novo));

                        zoomRatioLocal = novo;

                        // Feedback visual imediato.
                        atualizarZoomBadge(novo);
                        marcarPresetAtivo(
                                arredondarPreset(novo)
                        );

                        agendarEnvioZoom(novo);
                        return true;
                    }

                    @Override
                    public void onScaleEnd(
                            ScaleGestureDetector d
                    ) {

                        pinchAtivo = false;

                        // Garante o envio final do valor.
                        agendarEnvioZoom(zoomRatioLocal);
                    }
                }
        );

        // Captura o toque no container inteiro (sobre o video),
        // deixando os botoes/overlays responderem normalmente.
        android.view.View.OnTouchListener ouvinte =
                (v, evento) -> scaleDetector.onTouchEvent(evento);

        cameraPreview.setOnTouchListener(ouvinte);
    }

    // Envia o zoom (linear) ao servico com debounce, para nao
    // inundar o CameraX durante a pinça.
    private void agendarEnvioZoom(float ratio) {

        float max = Math.max(1.0f, zoomMaxLocal);

        zoomLinearPendente = linearDeRatio(ratio, max);

        if (zoomEnvioPendente != null) {
            zoomHandler.removeCallbacks(zoomEnvioPendente);
        }

        zoomEnvioPendente = () -> {

            Bundle extras = new Bundle();
            extras.putFloat("zoom", zoomLinearPendente);

            enviarAcaoServico(
                    CameraForegroundService.ACTION_SET_ZOOM,
                    extras
            );
        };

        zoomHandler.postDelayed(zoomEnvioPendente, 60);
    }

    // Aproxima o ratio do preset mais proximo (para acender o
    // chip certo enquanto a pinça esta no meio do caminho).
    private float arredondarPreset(float ratio) {

        float[] candidatos = {1.0f, 2.0f, 3.0f, 5.0f, 8.0f};

        float melhor = 1.0f;
        float dist = Float.MAX_VALUE;

        for (float c : candidatos) {

            if (c > zoomMaxLocal + 0.01f) {
                continue;
            }

            float d = Math.abs(c - ratio);

            if (d < dist) {
                dist = d;
                melhor = c;
            }
        }

        return melhor;
    }

    private void marcarPresetAtivo(float ratio) {

        if (zoomPresets == null) {
            return;
        }

        if (Math.abs(ratio - ultimoPresetRatio) < 0.001f) {
            return;
        }

        ultimoPresetRatio = ratio;

        for (int i = 0; i < zoomPresets.getChildCount(); i++) {

            android.view.View filho =
                    zoomPresets.getChildAt(i);

            if (!(filho instanceof Button)) {
                continue;
            }

            Button botao = (Button) filho;

            boolean ativo = Math.abs(
                    botao.getTag() != null
                            ? (Float) botao.getTag()
                            : 0.0f
                            - ratio
            ) < 0.05f;

            botao.setBackgroundResource(
                    ativo
                            ? R.drawable.cam_pill_ativo
                            : R.drawable.cam_pill
            );

            botao.setTextColor(
                    ContextCompat.getColor(
                            this,
                            ativo
                                    ? R.color.term_bg
                                    : R.color.term_fg
                    )
            );
        }
    }

    private void desenharZoomPresets() {

        if (zoomPresets == null) {
            return;
        }

        float max = Math.max(1.0f, zoomMaxLocal);

        // Assinatura evita reconstruir a fileira a cada status
        // (o status chega varias vezes por segundo).
        String assinatura = lenteLocal + ":" + max;

        if (assinatura.equals(presetsAssinatura)) {
            return;
        }

        presetsAssinatura = assinatura;

        zoomPresets.removeAllViews();

        float densidade =
                getResources().getDisplayMetrics().density;

        // Presets estilo camera nativa: 1x, 2x, 3x, 5x, 8x...
        // limitados ao maximo da lente atual.
        float[] candidatos = {
                1.0f, 2.0f, 3.0f, 5.0f, 8.0f
        };

        List<Float> presets = new ArrayList<>();

        for (float c : candidatos) {

            if (c <= max + 0.01f) {
                presets.add(c);
            }
        }

        // Garante o maximo exato como ultimo preset.
        if (!presets.contains(max) && max > 1.01f) {
            presets.add(max);
        }

        for (float ratio : presets) {

            Button botao = new Button(this);

            botao.setText(
                    String.format(Locale.US, "%.1fx", ratio)
            );
            botao.setAllCaps(false);

            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            Math.round(40 * densidade)
                    );

            lp.setMarginEnd(Math.round(8 * densidade));

            botao.setLayoutParams(lp);

            botao.setTag(ratio);

            botao.setBackgroundResource(R.drawable.cam_pill);
            botao.setTextColor(
                    ContextCompat.getColor(
                            this,
                            R.color.term_fg
                    )
            );
            botao.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    12f
            );
            botao.setPadding(
                    Math.round(16 * densidade),
                    0,
                    Math.round(16 * densidade),
                    0
            );
            botao.setMinWidth(0);
            botao.setMinimumWidth(
                    Math.round(54 * densidade)
            );
            botao.setMinHeight(0);
            botao.setMinimumHeight(0);
            botao.setStateListAnimator(null);

            botao.setOnClickListener(v -> irParaZoom(ratio));

            zoomPresets.addView(botao);
        }

        marcarPresetAtivo(1.0f);
    }

    // ============================================================
    // PINTURA DOS BOTOES DE CAMERA
    // ============================================================

    private void pintarFlash() {

        if (flashButton == null) {
            return;
        }

        if (flashLocal == ultimoFlash
                && flashDisponivelLocal == ultimoFlashDisp) {
            return;
        }

        ultimoFlash = flashLocal;
        ultimoFlashDisp = flashDisponivelLocal;

        flashButton.setText(
                flashLocal ? "flash ON" : "flash"
        );

        flashButton.setEnabled(flashDisponivelLocal);

        flashButton.setAlpha(
                flashDisponivelLocal ? 1.0f : 0.4f
        );

        flashButton.setBackgroundResource(
                flashLocal
                        ? R.drawable.cam_round_ativo
                        : R.drawable.cam_round
        );

        flashButton.setTextColor(
                ContextCompat.getColor(
                        this,
                        flashLocal
                                ? R.color.term_bg
                                : R.color.term_fg
                )
        );
    }

    private void pintarLente() {

        if (lenteButton == null) {
            return;
        }

        String chave = lenteLocal + "/" + lentesDisponiveisLocal.size();

        if (chave.equals(ultimaLente)) {
            return;
        }

        ultimaLente = chave;

        boolean frontal = "front".equals(lenteLocal);

        lenteButton.setText(
                frontal ? "frente" : "tras"
        );

        // Sem alternativa de lente, o botao fica indisponivel.
        boolean alternavel =
                lentesDisponiveisLocal.size() >= 2;

        lenteButton.setEnabled(alternavel);
        lenteButton.setAlpha(alternavel ? 1.0f : 0.4f);
    }

    private void pintarAutoRot() {

        if (autoRotButton == null) {
            return;
        }

        if (autoRotLocal == ultimoAutoRot) {
            return;
        }

        ultimoAutoRot = autoRotLocal;

        autoRotButton.setText(
                autoRotLocal ? "auto ON" : "auto"
        );

        autoRotButton.setBackgroundResource(
                autoRotLocal
                        ? R.drawable.cam_round_ativo
                        : R.drawable.cam_round
        );

        autoRotButton.setTextColor(
                ContextCompat.getColor(
                        this,
                        autoRotLocal
                                ? R.color.term_bg
                                : R.color.term_fg
                )
        );
    }

    private void pintarBolinha(boolean conectado) {

        if (bolinhaView == null) {
            return;
        }

        if (conectado == ultimaConexao) {
            return;
        }

        ultimaConexao = conectado;

        bolinhaView.setBackgroundResource(
                conectado
                        ? R.drawable.dot_green
                        : R.drawable.dot_red
        );
    }

    private void registrarStatusReceiver() {

        IntentFilter filter =
                new IntentFilter(
                        CameraForegroundService.ACTION_STATUS
                );

        if (
                Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.TIRAMISU
        ) {

            registerReceiver(
                    statusReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );

        } else {

            registerReceiver(
                    statusReceiver,
                    filter
            );
        }
    }

    private void verificarCamera() {

        if (
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
        ) {

            // Ja autorizada: garante que a camera do
            // servico realmente iniciou (em instalacao
            // nova o servico pode ter tentado antes da
            // permissao ser concedida).
            enviarAcaoServico(
                    CameraForegroundService
                            .ACTION_START_CAMERA
            );

        } else {

            cameraPermissionLauncher.launch(
                    Manifest.permission.CAMERA
            );
        }

        // Microfone: o servico captura e envia sempre que o
        // video estiver no ar. Pedimos aqui junto da camera.
        if (
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
        ) {

            audioPermissionLauncher.launch(
                    Manifest.permission.RECORD_AUDIO
            );
        }
    }

    // ============================================================
    // DETECAO DE CAMERAS (diagnostico)
    // ============================================================

private void detectarCamerasDisponiveis() {

    try {

        CameraManager cameraManager =
                (CameraManager) getSystemService(CAMERA_SERVICE);

        String[] cameraIds =
                cameraManager.getCameraIdList();

        Log.i(
                "LUCAO_CAMERA",
                "TOTAL CAMERAS EXPOSTAS: " +
                        cameraIds.length
        );

        for (String cameraId : cameraIds) {

            CameraCharacteristics info =
                    cameraManager.getCameraCharacteristics(
                            cameraId
                    );

            Integer facing =
                    info.get(
                            CameraCharacteristics.LENS_FACING
                    );

            float[] focais =
                    info.get(
                            CameraCharacteristics
                                    .LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                    );

            SizeF sensor =
                    info.get(
                            CameraCharacteristics
                                    .SENSOR_INFO_PHYSICAL_SIZE
                    );

            Boolean flash =
                    info.get(
                            CameraCharacteristics.FLASH_INFO_AVAILABLE
                    );

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

    java.util.Set<String> physicalIds =
            info.getPhysicalCameraIds();

    Log.i(
            "LUCAO_CAMERA",
            "ID=" + cameraId +
            " | CAMERAS FISICAS=" +
            physicalIds
    );

    Float maxDigitalZoom =
        info.get(
                CameraCharacteristics
                        .SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
        );

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

    android.util.Range<Float> zoomRange =
            info.get(
                    CameraCharacteristics
                            .CONTROL_ZOOM_RATIO_RANGE
            );

    Log.i(
            "LUCAO_CAMERA",
            "ID=" + cameraId +
            " | ZOOM RANGE=" + zoomRange +
            " | MAX DIGITAL=" + maxDigitalZoom
    );

} else {

    Log.i(
            "LUCAO_CAMERA",
            "ID=" + cameraId +
            " | MAX DIGITAL=" + maxDigitalZoom
    );
}


}


            String lado = "DESCONHECIDA";

            if (facing != null) {

                if (facing ==
                        CameraCharacteristics.LENS_FACING_BACK) {

                    lado = "TRASEIRA";

                } else if (facing ==
                        CameraCharacteristics.LENS_FACING_FRONT) {

                    lado = "FRONTAL";

                } else if (facing ==
                        CameraCharacteristics.LENS_FACING_EXTERNAL) {

                    lado = "EXTERNA";
                }
            }

            Log.i(
                    "LUCAO_CAMERA",
                    "ID=" + cameraId +
                    " | lado=" + lado +
                    " | focais=" +
                    java.util.Arrays.toString(focais) +
                    " | sensor=" + sensor +
                    " | flash=" + flash
            );
        }

    } catch (Exception e) {

        Log.e(
                "LUCAO_CAMERA",
                "ERRO AO DETECTAR CAMERAS",
                e
        );
    }
}

    // ============================================================
    // CONEXAO HTTP DE CONTROLE (5050)
    // ============================================================

    private void configurarHostPc() {

        android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        String host = getIntent() != null
                ? getIntent().getStringExtra("pcHost")
                : null;

        if (host == null || host.trim().isEmpty()) {
            host = prefs.getString(PREF_PC_HOST, "");
        }

        if (host == null) {
            host = "";
        }

        host = host.trim();

        pcHostInput.setText(host);

        if (!host.isEmpty()) {

            prefs.edit()
                    .putString(PREF_PC_HOST, host)
                    .apply();

            enviarHostAoServico(host);
        }

        pcHostInput.setOnEditorActionListener(
                (v, actionId, event) -> {

                    salvarHostPc();

                    return true;
                }
        );

        pcHostInput.setOnFocusChangeListener(
                (v, temFoco) -> {

                    if (!temFoco) {
                        salvarHostPc();
                    }
                }
        );
    }

    private void salvarHostPc() {

        String host =
                pcHostInput.getText().toString().trim();

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_PC_HOST, host)
                .apply();

        enviarHostAoServico(host);

        statusText.setText(
                host.isEmpty()
                        ? "host automatico"
                        : "host " + host
        );

        statusText.setTextColor(
                ContextCompat.getColor(
                        this,
                        host.isEmpty()
                                ? R.color.term_muted
                                : R.color.term_primary
                )
        );
    }

    private void enviarHostAoServico(String host) {

        Bundle extras = new Bundle();

        extras.putString("host", host);

        enviarAcaoServico(
                CameraForegroundService.ACTION_SET_HOST,
                extras
        );
    }

    private void testarConexao() {

        Log.i(TAG, "BOTAO CONECTAR");

        statusText.setText("conectando...");
        statusText.setTextColor(
                ContextCompat.getColor(
                        this,
                        R.color.term_warning
                )
        );

        connectButton.setEnabled(false);

        new Thread(() -> {

            String wifi = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(PREF_PC_HOST, "");

            List<String> hosts = new ArrayList<>();

            // USB (ADB reverse) tem prioridade.
            hosts.add("127.0.0.1");

            if (wifi != null && !wifi.trim().isEmpty()) {

                wifi = wifi.trim();

                if (!hosts.contains(wifi)) {
                    hosts.add(wifi);
                }
            }

            for (String host : hosts) {

                HttpURLConnection conexao = null;

                try {

                    URL url = new URL(
                            "http://" + host + ":5050/api/devices"
                    );

                    conexao = (HttpURLConnection) url.openConnection();

                    conexao.setRequestMethod("GET");
                    conexao.setConnectTimeout(2000);
                    conexao.setReadTimeout(2000);

                    int response = conexao.getResponseCode();

                    Log.i(
                            TAG,
                            "HTTP " + host + " -> " + response
                    );

                    if (response >= 200 && response < 300) {

                        String transporte =
                                "127.0.0.1".equals(host)
                                        ? "usb"
                                        : "wifi";

                        runOnUiThread(() -> {

                            statusText.setText(
                                    "online · " + transporte
                            );

                            statusText.setTextColor(
                                    ContextCompat.getColor(
                                            MainActivity.this,
                                            R.color.term_success
                                    )
                            );

                            connectButton.setText("conectado");
                            connectButton.setEnabled(true);
                        });

                        return;
                    }

                } catch (IOException e) {

                    Log.w(
                            TAG,
                            "FALHA EM " + host + ": " + e.getMessage()
                    );

                } finally {

                    if (conexao != null) {
                        conexao.disconnect();
                    }
                }
            }

            mostrarErro("pc offline");

        }).start();
    }

    private void mostrarErro(String mensagem) {

        runOnUiThread(() -> {

            statusText.setText(mensagem);
            statusText.setTextColor(
                    ContextCompat.getColor(
                            this,
                            R.color.term_danger
                    )
            );

            connectButton.setText(
                    "reconectar"
            );

            connectButton.setEnabled(true);
        });
    }
}
