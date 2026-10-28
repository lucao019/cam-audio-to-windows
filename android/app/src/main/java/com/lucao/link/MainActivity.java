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
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
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

    private SeekBar zoomSeekBar;
    private TextView zoomValueText;
    private TextView zoomMinText;
    private TextView zoomMaxText;

    private android.widget.CheckBox webcamSwitch;

    private EditText pcHostInput;

    private boolean sincronizandoWebcam = false;

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

                        float linear =
                                intent.getFloatExtra(
                                        "linearZoom",
                                        0.0f
                                );

                        zoomValueText.setText(
                                String.format(
                                        Locale.US,
                                        "%.1fx",
                                        zoom
                                )
                        );

                        zoomMinText.setText(
                                String.format(
                                        Locale.US,
                                        "%.1fx",
                                        minimo
                                )
                        );

                        zoomMaxText.setText(
                                String.format(
                                        Locale.US,
                                        "%.1fx",
                                        maximo
                                )
                        );

                        // fromUser=false, entao nao gera
                        // eco de comando de volta ao servico.
                        zoomSeekBar.setProgress(
                                Math.round(
                                        linear * 100
                                )
                        );
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

        zoomSeekBar = findViewById(R.id.zoomSeekBar);
        zoomValueText = findViewById(R.id.zoomValueText);
        zoomMinText = findViewById(R.id.zoomMinText);
        zoomMaxText = findViewById(R.id.zoomMaxText);

        webcamSwitch = findViewById(R.id.webcamSwitch);

        pcHostInput = findViewById(R.id.pcHostInput);

        configurarHostPc();

        detectarCamerasDisponiveis();

        // ----------------------------
        // ZOOM (UI -> servico)
        // ----------------------------

        zoomSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {

                        if (!fromUser) {
                            return;
                        }

                        Bundle extras = new Bundle();

                        extras.putFloat(
                                "zoom",
                                progress / 100.0f
                        );

                        enviarAcaoServico(
                                CameraForegroundService
                                        .ACTION_SET_ZOOM,
                                extras
                        );
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }
                }
        );

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
