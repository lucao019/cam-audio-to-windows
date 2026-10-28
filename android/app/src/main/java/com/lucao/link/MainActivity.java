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
import java.util.Locale;

/**
 * Apenas UI.
 * O pipeline de video (camera -> H264 -> TCP) pertence
 * exclusivamente ao CameraForegroundService.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LUCAO_UI";

    private static final String SERVER =
            "http://127.0.0.1:5050/";

    private TextView statusText;
    private TextView connectionBadge;

    private Button connectButton;

    private PreviewView cameraPreview;

    private SeekBar zoomSeekBar;
    private TextView zoomValueText;
    private TextView zoomMinText;
    private TextView zoomMaxText;

    private android.widget.CheckBox webcamSwitch;

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

    private void testarConexao() {

        Log.i(
        TAG,
        "BOTAO CONECTAR - servidor=" + SERVER
);

        statusText.setText("conectando...");
        statusText.setTextColor(
                ContextCompat.getColor(
                        this,
                        R.color.term_warning
                )
        );

        connectButton.setEnabled(false);

        new Thread(() -> {

            HttpURLConnection connection = null;

            try {

                URL url =
                        new URL(SERVER);

                connection =
                        (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);

                int response =
                        connection.getResponseCode();

                        Log.i(
        TAG,
        "HTTP RESPONSE=" + response
);


                if (
                        response >= 200 &&
                        response < 300
                ) {

                    runOnUiThread(() -> {

                        statusText.setText(
                                "online"
                        );

                        statusText.setTextColor(
                                ContextCompat.getColor(
                                        MainActivity.this,
                                        R.color.term_success
                                )
                        );

                        connectButton.setText(
                                "conectado"
                        );

                        connectButton.setEnabled(true);
                    });

                } else {

                    mostrarErro(
                            "ERRO HTTP " + response
                    );
                }

            } catch (IOException e) {

    Log.e(
            TAG,
            "ERRO NA CONEXAO HTTP",
            e
    );

    mostrarErro(
            "pc offline"
    );

            } finally {

                if (connection != null) {
                    connection.disconnect();
                }
            }

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
