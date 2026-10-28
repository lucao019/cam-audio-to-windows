package com.lucao.link;

import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.util.Range;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import android.hardware.camera2.CaptureRequest;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;

public class CameraForegroundService extends Service
        implements LifecycleOwner {

    // ============================================================
    // FOREGROUND SERVICE
    // ============================================================

    private static final String CHANNEL_ID =
            "lucao_link_camera";

    private static final int NOTIFICATION_ID = 1001;

    private LifecycleRegistry lifecycleRegistry;

    // ============================================================
    // VIDEO
    // ============================================================

    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int VIDEO_FPS = 30;
    private static final int VIDEO_BITRATE = 2_000_000;

    // Host 127.0.0.1 = modo dedicado USB via ADB Reverse
    // (companion.js monta os tuneis tcp:5050 e tcp:5051).
    //
    // Se o USB nao estiver ativo, o servico tenta o host Wi-Fi
    // salvo (pcHost), preenchido pela MainActivity/companion.
    private static final String USB_HOST = "127.0.0.1";
    private static final int VIDEO_PORT = 5051;
    private static final int CONTROL_PORT = 5050;

    // ============================================================
    // IDENTIDADE / PREFERENCIAS
    // ============================================================

    private static final String PREFS = "lucao_link";
    private static final String PREF_DEVICE_ID = "deviceId";
    private static final String PREF_DEVICE_NAME = "deviceName";
    private static final String PREF_PC_HOST = "pcHost";

    private volatile String deviceId = "";
    private volatile String deviceName = "android";
    private volatile String pcHost = "";

    private volatile String hostAtivo = USB_HOST;
    private volatile String transporteAtivo = "usb";

    private volatile double fpsAtual = 0;
    private volatile double kbpsAtual = 0;

    // ============================================================
    // ACOES DE CONTROLE (recebidas da MainActivity)
    // ============================================================

    public static final String ACTION_SET_ZOOM =
            "com.lucao.link.ACTION_SET_ZOOM";
    public static final String ACTION_SET_CAMERA_ACTIVE =
            "com.lucao.link.ACTION_SET_CAMERA_ACTIVE";
    public static final String ACTION_START_CAMERA =
            "com.lucao.link.ACTION_START_CAMERA";
    public static final String ACTION_REQUEST_STATUS =
            "com.lucao.link.ACTION_REQUEST_STATUS";
    public static final String ACTION_SET_HOST =
            "com.lucao.link.ACTION_SET_HOST";

    // Broadcast de status enviado de volta para a UI
    public static final String ACTION_STATUS =
            "com.lucao.link.ACTION_STATUS";

    // ============================================================
    // H264 / TCP
    // ============================================================

    private volatile MediaCodec h264Encoder;

    private volatile byte[] h264Sps;
    private volatile byte[] h264Pps;

    private volatile Socket videoSocket;
    private volatile OutputStream videoOutput;

    // ============================================================
    // EXECUTORES
    // ============================================================

    private ExecutorService cameraExecutor;
    private ExecutorService networkExecutor;
    private ExecutorService videoWriterExecutor;

    private ProcessCameraProvider cameraProvider;
    private Camera cameraAtual;

    // Superficie opcional enviada pela MainActivity (PreviewView).
    private static volatile Preview.SurfaceProvider previewProvider;

    public static void setPreviewProvider(
            Preview.SurfaceProvider provider
    ) {
        previewProvider = provider;
    }

    // ============================================================
    // ESTADO
    // ============================================================

    private volatile boolean pcConectado = false;
    private volatile boolean cameraAtiva = true;
    private volatile boolean solicitarKeyframeH264 = false;
    private volatile boolean servicoAtivo = true;
    private volatile long ultimaEscritaVideo = 0;
    private volatile long ultimoFrameCamera = 0;
    private volatile long ultimoReinicioCamera = 0;
    private final java.util.concurrent.atomic.AtomicBoolean reconectandoVideo =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // ============================================================
    // ESTATISTICAS H264
    // ============================================================

    private long h264JanelaInicioMs = 0;
    private int h264FramesEntrada = 0;
    private int h264FramesSaida = 0;
    private long h264BytesSaida = 0;

    // ============================================================
    // SERVICE
    // ============================================================

    @Override
public void onCreate() {
    super.onCreate();

    Log.i("LUCAO_SERVICE", "CameraForegroundService.onCreate()");

    carregarIdentidade();

listarFpsCamera();

cameraExecutor =
        Executors.newSingleThreadExecutor();

    networkExecutor =
        Executors.newFixedThreadPool(4);

videoWriterExecutor =
        Executors.newSingleThreadExecutor();

    // Lifecycle usado pelo CameraX
    lifecycleRegistry =
            new LifecycleRegistry(this);

    lifecycleRegistry.setCurrentState(
            Lifecycle.State.CREATED
    );

    // Foreground Service precisa da notificaÃ§Ã£o
    criarCanalNotificacao();

    Notification notification =
            new NotificationCompat.Builder(
                    this,
                    CHANNEL_ID
            )
                    .setContentTitle("LUCAO LINK")
                    .setContentText(
                            "Webcam conectada ao computador"
                    )
                    .setSmallIcon(
                            android.R.drawable.presence_video_online
                    )
                    .setOngoing(true)
                    .setPriority(
                            NotificationCompat.PRIORITY_LOW
                    )
                    .build();

    startForeground(
            NOTIFICATION_ID,
            notification
    );

    // Agora o Service estÃ¡ ativo para o CameraX
    lifecycleRegistry.setCurrentState(
            Lifecycle.State.STARTED
    );

// TCP + encoder rodam fora da main thread
networkExecutor.execute(() -> {

    Log.i(
            "LUCAO_SERVICE",
            "INICIANDO PIPELINE"
    );

    conectarVideoTcp();

    if (videoOutput == null) {

        Log.w(
                "LUCAO_SERVICE",
                "TCP 5051 INDISPONIVEL - AGUARDANDO PC"
        );

        iniciarReconexaoVideo();

    } else {

        pcConectado = true;
        broadcastStatus();
    }

    prepararEncoderH264();
    iniciarPollingControle();
    iniciarEnvioStatus();

    Log.i(
            "LUCAO_SERVICE",
            "SOLICITANDO INICIO DA CAMERA"
    );

    ContextCompat
            .getMainExecutor(this)
            .execute(() -> {

                Log.i(
                        "LUCAO_SERVICE",
                        "EXECUTANDO iniciarCamera() NA MAIN THREAD"
                );

                iniciarCamera();
            });
});

}


    // ============================================================
    // NOTIFICACAO
    // ============================================================

    private void criarCanalNotificacao() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "LUCAO LINK Webcam",
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "MantÃ©m a webcam LUCAO LINK ativa"
            );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            manager.createNotificationChannel(
                    channel
            );
        }
    }

    // ============================================================
    // TCP VIDEO
    // ============================================================


    private synchronized void desconectarVideoTcp() {

    OutputStream output = videoOutput;
    Socket socket = videoSocket;

    videoOutput = null;
    videoSocket = null;
    pcConectado = false;
    fpsAtual = 0;
    kbpsAtual = 0;

    if (output != null) {
        try {
            output.close();
        } catch (Exception ignored) {
        }
    }

    if (socket != null) {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    Log.w(
            "LUCAO_TCP",
            "CONEXAO TCP DESCARTADA"
    );

    broadcastStatus();
}

private boolean enviarCabecalhoH264() {

    OutputStream output =
            videoOutput;

    if (output == null) {
        return false;
    }

    byte[] sps =
            h264Sps;

    byte[] pps =
            h264Pps;

    // Primeira inicializaÃ§Ã£o:
    // o encoder ainda pode nÃ£o ter produzido SPS/PPS.
    if (sps == null || pps == null) {

        Log.w(
                "LUCAO_TCP",
                "SPS/PPS AINDA NAO DISPONIVEIS"
        );

        return true;
    }

    try {

        output.write(sps);
        output.write(pps);
        output.flush();

        Log.i(
                "LUCAO_TCP",
                "SPS/PPS REENVIADOS: " +
                        sps.length +
                        " + " +
                        pps.length +
                        " bytes"
        );

        return true;

    } catch (IOException e) {

        Log.e(
                "LUCAO_TCP",
                "ERRO AO REENVIAR SPS/PPS: " +
                        e.getMessage()
        );

        desconectarVideoTcp();

        return false;
    }
}

private void iniciarReconexaoVideo() {

    if (!servicoAtivo) {
        return;
    }

    if (!reconectandoVideo.compareAndSet(false, true)) {
        return;
    }

    ExecutorService executor = networkExecutor;

    if (executor == null || executor.isShutdown()) {
        reconectandoVideo.set(false);
        return;
    }

    executor.execute(() -> {

        Log.w(
                "LUCAO_TCP",
                "INICIANDO RECONEXAO AUTOMATICA"
        );

        try {

            while (
                    servicoAtivo &&
                    videoOutput == null
            ) {

                conectarVideoTcp();

                if (videoOutput != null) {

    if (!enviarCabecalhoH264()) {

        Log.w(
                "LUCAO_TCP",
                "FALHA NO CABECALHO - CONTINUANDO RECONEXAO"
        );

        continue;
    }

    pcConectado = true;

    solicitarKeyframeH264 = true;

    broadcastStatus();

    Log.i(
            "LUCAO_TCP",
            "VIDEO TCP RECONECTADO - KEYFRAME PENDENTE"
    );

    break;
}

                Log.w(
                        "LUCAO_TCP",
                        "PC INDISPONIVEL - NOVA TENTATIVA EM 1s"
                );

                try {

                    Thread.sleep(1000);

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                    break;
                }
            }

        } finally {

            reconectandoVideo.set(false);
        }
    });
}

    private synchronized void conectarVideoTcp() {

        if (
                videoSocket != null &&
                videoSocket.isConnected() &&
                !videoSocket.isClosed()
        ) {
            return;
        }

        for (String host : candidatosHost()) {

            Socket socket = null;

            try {

                socket = new Socket();
                socket.connect(
                        new java.net.InetSocketAddress(host, VIDEO_PORT),
                        1500
                );
                socket.setTcpNoDelay(true);

                OutputStream output =
                        socket.getOutputStream();

                // Handshake: identifica o aparelho para o receiver
                // (uma janela ffplay por device).
                output.write(
                        montarHandshake(host).getBytes("UTF-8")
                );
                output.flush();

                videoSocket = socket;
                videoOutput = output;

                hostAtivo = host;
                transporteAtivo =
                        USB_HOST.equals(host) ? "usb" : "wifi";

                ultimaEscritaVideo =
                        System.currentTimeMillis();

                Log.i(
                        "LUCAO_TCP",
                        "VIDEO TCP CONECTADO: " +
                                host +
                                ":" +
                                VIDEO_PORT +
                                " (" +
                                transporteAtivo +
                                ")"
                );

                broadcastStatus();

                return;

            } catch (Exception e) {

                Log.w(
                        "LUCAO_TCP",
                        "FALHA AO CONECTAR " +
                                host +
                                ":5051: " +
                                e.getMessage()
                );

                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }

                videoSocket = null;
                videoOutput = null;
            }
        }
    }

    // ------------------------------------------------------------
    // IDENTIDADE / HOSTS
    // ------------------------------------------------------------

    private void carregarIdentidade() {

        android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        deviceId = prefs.getString(PREF_DEVICE_ID, "");

        if (deviceId == null || deviceId.isEmpty()) {

            deviceId = UUID.randomUUID().toString();

            prefs.edit()
                    .putString(PREF_DEVICE_ID, deviceId)
                    .apply();
        }

        deviceName = prefs.getString(PREF_DEVICE_NAME, "");

        if (deviceName == null || deviceName.isEmpty()) {

            deviceName = Build.MODEL;

            prefs.edit()
                    .putString(PREF_DEVICE_NAME, deviceName)
                    .apply();
        }

        pcHost = prefs.getString(PREF_PC_HOST, "");

        if (pcHost == null) {
            pcHost = "";
        }
    }

    /**
     * Nome sem espacos: o handshake e separado por espacos.
     */
    private String nomeHandshake() {

        String nome = deviceName == null ? "android" : deviceName;

        nome = nome.replaceAll("[\\s/]+", "_");

        return nome.isEmpty() ? "android" : nome;
    }

    private String montarHandshake(String host) {

        String transporte =
                USB_HOST.equals(host) ? "usb" : "wifi";

        return "LUCAO/1 " +
                deviceId +
                " " +
                nomeHandshake() +
                " " +
                transporte +
                "\n";
    }

    /**
     * USB (127.0.0.1 via ADB reverse) tem prioridade; depois o
     * host Wi-Fi informado pela MainActivity/companion.
     */
    private List<String> candidatosHost() {

        List<String> lista = new ArrayList<>();

        lista.add(USB_HOST);

        String wifi = pcHost == null ? "" : pcHost.trim();

        if (!wifi.isEmpty() && !lista.contains(wifi)) {
            lista.add(wifi);
        }

        return lista;
    }

    private String ipLocal() {

        try {

            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            String primeiro = "";

            while (interfaces.hasMoreElements()) {

                NetworkInterface ni = interfaces.nextElement();

                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }

                Enumeration<InetAddress> enderecos =
                        ni.getInetAddresses();

                while (enderecos.hasMoreElements()) {

                    InetAddress endereco = enderecos.nextElement();

                    if (
                            !(endereco instanceof Inet4Address) ||
                            endereco.isLoopbackAddress()
                    ) {
                        continue;
                    }

                    String ip = endereco.getHostAddress();

                    // Prefere a interface Wi-Fi (o PC so alcanca
                    // esse IP; dados moveis nao servem).
                    String nome = ni.getName().toLowerCase();

                    if (
                            nome.startsWith("wlan") ||
                            nome.startsWith("wifi") ||
                            nome.startsWith("ap")
                    ) {
                        return ip;
                    }

                    if (primeiro.isEmpty()) {
                        primeiro = ip;
                    }
                }
            }

            return primeiro;

        } catch (Exception ignored) {
        }

        return "";
    }

    // ------------------------------------------------------------
    // HTTP DE CONTROLE
    // ------------------------------------------------------------

    private String requisicaoHttp(
            String metodo,
            String host,
            String caminho,
            String corpo
    ) throws Exception {

        URL url = new URL(
                "http://" + host + ":" + CONTROL_PORT + caminho
        );

        HttpURLConnection conn =
                (HttpURLConnection) url.openConnection();

        conn.setRequestMethod(metodo);
        conn.setConnectTimeout(800);
        conn.setReadTimeout(1200);

        if (corpo != null) {

            conn.setDoOutput(true);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json"
            );

            OutputStream out = conn.getOutputStream();
            out.write(corpo.getBytes("UTF-8"));
            out.flush();
            out.close();
        }

        int codigo = conn.getResponseCode();

        InputStream in = codigo >= 200 && codigo < 300
                ? conn.getInputStream()
                : conn.getErrorStream();

        String resposta = "";

        if (in != null) {

            Scanner scanner =
                    new Scanner(in, "UTF-8").useDelimiter("\\A");

            resposta = scanner.hasNext()
                    ? scanner.next()
                    : "";

            in.close();
        }

        conn.disconnect();

        if (codigo < 200 || codigo >= 300) {
            throw new IOException("HTTP " + codigo);
        }

        return resposta;
    }

    /**
     * Envia o estado atual para o painel do PC (~1x por segundo).
     */
    private void enviarStatus() {

        JSONObject json = new JSONObject();

        try {

            json.put("id", deviceId);
            json.put("nome", deviceName);
            json.put("transporte", transporteAtivo);
            json.put("ip", ipLocal());
            json.put("webcam", cameraAtiva);
            json.put("fps", fpsAtual);
            json.put("kbps", kbpsAtual);

            Camera camera = cameraAtual;

            if (camera != null) {

                androidx.camera.core.ZoomState zoomState =
                        camera
                                .getCameraInfo()
                                .getZoomState()
                                .getValue();

                if (zoomState != null) {

                    json.put("zoom", zoomState.getZoomRatio());
                    json.put("zoomMin", zoomState.getMinZoomRatio());
                    json.put("zoomMax", zoomState.getMaxZoomRatio());
                }
            }

        } catch (Exception e) {

            Log.w(
                    "LUCAO_CONTROL",
                    "FALHA AO MONTAR STATUS: " + e.getMessage()
            );

            return;
        }

        for (String host : candidatosHost()) {

            try {

                requisicaoHttp(
                        "POST",
                        host,
                        "/api/status",
                        json.toString()
                );

                return;

            } catch (Exception ignored) {
            }
        }
    }

    private void iniciarEnvioStatus() {

        networkExecutor.execute(() -> {

            while (servicoAtivo) {

                try {

                    Thread.sleep(1000);

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                    break;
                }

                if (!pcConectado) {
                    continue;
                }

                // Watchdog da camera: o CameraX pode perder a sessao
                // (ex.: Activity destruida, surface invalida) e parar
                // de entregar frames sem avisar. Sem frames por 3s,
                // religa a camera (no maximo a cada 10s).
                long agora = System.currentTimeMillis();

                if (
                        cameraAtiva &&
                        servicoAtivo &&
                        ultimoFrameCamera > 0 &&
                        agora - ultimoFrameCamera > 3000 &&
                        agora - ultimoReinicioCamera > 10000
                ) {

                    ultimoReinicioCamera = agora;
                    ultimoFrameCamera = agora;
                    solicitarKeyframeH264 = true;

                    Log.w(
                            "LUCAO_CAMERA",
                            "SEM FRAMES HA 3s - REINICIANDO CAMERA"
                    );

                    ContextCompat
                            .getMainExecutor(this)
                            .execute(this::iniciarCamera);
                }

                // Watchdog: se o PC sumiu, a escrita no socket pode
                // ficar bloqueada para sempre. Sem progresso por 5s,
                // derruba e reconecta (o write travado e liberado).
                if (
                        cameraAtiva &&
                        videoOutput != null &&
                        ultimaEscritaVideo > 0 &&
                        System.currentTimeMillis() -
                                ultimaEscritaVideo > 5000
                ) {

                    Log.w(
                            "LUCAO_TCP",
                            "SEM ESCRITA DE VIDEO HA 5s - RECONECTANDO"
                    );

                    desconectarVideoTcp();
                    iniciarReconexaoVideo();
                    continue;
                }

                try {
                    enviarStatus();
                } catch (Exception e) {

                    Log.w(
                            "LUCAO_CONTROL",
                            "STATUS FALHOU: " + e.getMessage()
                    );
                }
            }
        });
    }

    // ============================================================
    // STATUS -> UI (broadcast)
    // ============================================================

    /**
     * Envia o estado do pipeline de video de volta para a
     * MainActivity: conexao TCP + estado atual do zoom.
     */
    private void broadcastStatus() {

    Intent intent = new Intent(ACTION_STATUS);

    intent.setPackage(getPackageName());

    intent.putExtra("conectado", pcConectado);
    intent.putExtra("webcam", cameraAtiva);
    intent.putExtra("transporte", transporteAtivo);
    intent.putExtra("host", hostAtivo);

    Camera camera = cameraAtual;

    if (camera != null) {

        androidx.camera.core.ZoomState zoomState =
                camera
                        .getCameraInfo()
                        .getZoomState()
                        .getValue();

        if (zoomState != null) {

            intent.putExtra(
                    "zoom",
                    zoomState.getZoomRatio()
            );

            intent.putExtra(
                    "zoomMin",
                    zoomState.getMinZoomRatio()
            );

            intent.putExtra(
                    "zoomMax",
                    zoomState.getMaxZoomRatio()
            );

            intent.putExtra(
                    "linearZoom",
                    zoomState.getLinearZoom()
            );
        }
    }

    sendBroadcast(intent);
}

    // ============================================================
// CAMERAX
// ============================================================

private void listarFpsCamera() {

    try {

        android.hardware.camera2.CameraManager cameraManager =
                (android.hardware.camera2.CameraManager)
                        getSystemService(CAMERA_SERVICE);

        for (String cameraId : cameraManager.getCameraIdList()) {

            android.hardware.camera2.CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);

            Integer facing =
                    characteristics.get(
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING
                    );

            if (
                    facing != null &&
                    facing ==
                            android.hardware.camera2.CameraCharacteristics
                                    .LENS_FACING_BACK
            ) {

                android.util.Range<Integer>[] fpsRanges =
                        characteristics.get(
                                android.hardware.camera2.CameraCharacteristics
                                        .CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                        );

                if (fpsRanges != null) {

                    for (android.util.Range<Integer> range : fpsRanges) {

                        Log.i(
                                "LUCAO_FPS",
                                "CAMERA " + cameraId +
                                        " FPS DISPONIVEL: " +
                                        range
                        );
                    }
                }
            }
        }

        } catch (Exception e) {

        Log.e(
                "LUCAO_FPS",
                "ERRO AO LISTAR FPS",
                e
        );
    }
}


    // ============================================================
// ANALISE DE FRAMES
// ============================================================

@ExperimentalCamera2Interop
private void iniciarCamera() {

    Log.i(
            "LUCAO_CAMERA",
            "ENTROU EM iniciarCamera()"
    );

    // Referencia para o watchdog de frames.
    ultimoFrameCamera = System.currentTimeMillis();

    ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
            ProcessCameraProvider.getInstance(this);

    cameraProviderFuture.addListener(() -> {

        try {

            cameraProvider =
                    cameraProviderFuture.get();

            ResolutionSelector resolutionSelector =
                    new ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                    AspectRatioStrategy
                                            .RATIO_4_3_FALLBACK_AUTO_STRATEGY
                            )
                            .setResolutionStrategy(
                                    new ResolutionStrategy(
                                            new android.util.Size(
                                                    VIDEO_WIDTH,
                                                    VIDEO_HEIGHT
                                            ),
                                            ResolutionStrategy
                                                    .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                    )
                            )
                            .build();

            ImageAnalysis.Builder imageAnalysisBuilder =
        new ImageAnalysis.Builder()
                .setResolutionSelector(
                        resolutionSelector
                )
                .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                );

new Camera2Interop.Extender<>(imageAnalysisBuilder)
        .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                new Range<>(30, 30)
        );

ImageAnalysis imageAnalysis =
        imageAnalysisBuilder.build();

Log.i(
        "LUCAO_FPS",
        "FPS SOLICITADO AO CAMERAX: [30,30]"
);

            imageAnalysis.setAnalyzer(
                    cameraExecutor,
                    this::analisarFrame
            );

            CameraSelector cameraSelector =
                    CameraSelector.DEFAULT_BACK_CAMERA;

            // Preview local opcional (so quando a Activity esta
            // visivel e entregou uma SurfaceProvider).
            Preview.SurfaceProvider provider = previewProvider;

            Preview preview = null;

            if (provider != null) {

                preview = new Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build();

                preview.setSurfaceProvider(provider);
            }

            cameraProvider.unbindAll();

            if (preview != null) {

                cameraAtual =
                        cameraProvider.bindToLifecycle(
                                this,
                                cameraSelector,
                                preview,
                                imageAnalysis
                        );

            } else {

                cameraAtual =
                        cameraProvider.bindToLifecycle(
                                this,
                                cameraSelector,
                                imageAnalysis
                        );
            }

            Log.i(
                    "LUCAO_CAMERA",
                    "CAMERA DO SERVICE INICIADA: " +
                            VIDEO_WIDTH + "x" +
                            VIDEO_HEIGHT +
                            " @ " +
                            VIDEO_FPS +
                            " FPS"
            );

            // Mantem a UI atualizada com o zoom real da camera.
            cameraAtual
                    .getCameraInfo()
                    .getZoomState()
                    .observe(
                            this,
                            zoomState -> {

                                if (zoomState == null) {
                                    return;
                                }

                                broadcastStatus();
                            }
                    );

        } catch (Exception e) {

            Log.e(
                    "LUCAO_CAMERA",
                    "ERRO AO INICIAR CAMERA NO SERVICE",
                    e
            );
        }

    }, ContextCompat.getMainExecutor(this));
}
        private void analisarFrame(ImageProxy image) {

    try {

        ultimoFrameCamera = System.currentTimeMillis();

        if (!pcConectado) {
            return;
        }

        if (!cameraAtiva) {
            return;
        }

        processarFrameH264(image);

    } catch (Exception e) {

        Log.e(
                "LUCAO_H264",
                "ERRO AO ANALISAR FRAME",
                e
        );

    } finally {

        image.close();
    }
}
    // ============================================================
    // YUV_420_888 -> NV12
    // ============================================================

    private byte[] converterParaNV12(
            ImageProxy image
    ) {

        int width = image.getWidth();
        int height = image.getHeight();

        ImageProxy.PlaneProxy[] planes =
                image.getPlanes();

        byte[] nv12 =
                new byte[
                        width * height * 3 / 2
                ];

        int pos = 0;

        // ----------------------------
        // Y
        // ----------------------------

        ByteBuffer yBuffer =
                planes[0]
                        .getBuffer()
                        .duplicate();

        int yBase =
                yBuffer.position();

        int yRowStride =
                planes[0].getRowStride();

        int yPixelStride =
                planes[0].getPixelStride();

        if (yPixelStride == 1) {

            // Caminho rÃ¡pido.
            // Foi o que permitiu manter ~30 FPS.

            for (
                    int row = 0;
                    row < height;
                    row++
            ) {

                int rowStart =
                        yBase +
                        row * yRowStride;

                yBuffer.position(
                        rowStart
                );

                yBuffer.get(
                        nv12,
                        pos,
                        width
                );

                pos += width;
            }

        } else {

            for (
                    int row = 0;
                    row < height;
                    row++
            ) {

                for (
                        int col = 0;
                        col < width;
                        col++
                ) {

                    nv12[pos++] =
                            yBuffer.get(
                                    yBase +
                                    row * yRowStride +
                                    col * yPixelStride
                            );
                }
            }
        }

        // ----------------------------
        // UV
        // ----------------------------

        ByteBuffer uBuffer =
                planes[1]
                        .getBuffer()
                        .duplicate();

        ByteBuffer vBuffer =
                planes[2]
                        .getBuffer()
                        .duplicate();

        int uBase =
                uBuffer.position();

        int vBase =
                vBuffer.position();

        int uRowStride =
                planes[1].getRowStride();

        int vRowStride =
                planes[2].getRowStride();

        int uPixelStride =
                planes[1].getPixelStride();

        int vPixelStride =
                planes[2].getPixelStride();

        for (
                int row = 0;
                row < height / 2;
                row++
        ) {

            for (
                    int col = 0;
                    col < width / 2;
                    col++
            ) {

                nv12[pos++] =
                        uBuffer.get(
                                uBase +
                                row * uRowStride +
                                col * uPixelStride
                        );

                nv12[pos++] =
                        vBuffer.get(
                                vBase +
                                row * vRowStride +
                                col * vPixelStride
                        );
            }
        }

        return nv12;
    }

    // ============================================================
    // FRAME -> MEDIACODEC -> TCP
    // ============================================================

    private void processarFrameH264(
            ImageProxy image
    ) {

        MediaCodec codec =
                h264Encoder;

        if (codec == null) {
            return;
        }

        // ----------------------------
        // KEYFRAME
        // ----------------------------

        if (solicitarKeyframeH264) {

            try {

                Bundle parametros =
                        new Bundle();

                parametros.putInt(
                        MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,
                        0
                );

                codec.setParameters(
                        parametros
                );

                solicitarKeyframeH264 =
                        false;

                Log.i(
                        "LUCAO_H264",
                        "KEYFRAME SOLICITADO"
                );

            } catch (Exception e) {

                Log.e(
                        "LUCAO_H264",
                        "ERRO AO SOLICITAR KEYFRAME",
                        e
                );
            }
        }

        // ----------------------------
        // RESOLUCAO
        // ----------------------------

        if (
                image.getWidth() != VIDEO_WIDTH ||
                image.getHeight() != VIDEO_HEIGHT
        ) {

            Log.w(
                    "LUCAO_H264",
                    "FRAME IGNORADO: camera=" +
                            image.getWidth() +
                            "x" +
                            image.getHeight() +
                            " encoder=" +
                            VIDEO_WIDTH +
                            "x" +
                            VIDEO_HEIGHT
            );

            return;
        }

        try {

            // ====================================================
            // ENTRADA
            // CameraX -> NV12 -> MediaCodec
            // ====================================================

            int inputIndex =
                    codec.dequeueInputBuffer(0);

            if (inputIndex >= 0) {

                byte[] nv12 =
                        converterParaNV12(
                                image
                        );

                ByteBuffer inputBuffer =
                        codec.getInputBuffer(
                                inputIndex
                        );

                if (inputBuffer != null) {

                    inputBuffer.clear();

                    if (
                            inputBuffer.remaining() >=
                            nv12.length
                    ) {

                        inputBuffer.put(
                                nv12
                        );

                        long presentationTimeUs =
                                image
                                        .getImageInfo()
                                        .getTimestamp()
                                        / 1000L;

                        codec.queueInputBuffer(
                                inputIndex,
                                0,
                                nv12.length,
                                presentationTimeUs,
                                0
                        );

                        h264FramesEntrada++;

                    } else {

                        Log.e(
                                "LUCAO_H264",
                                "BUFFER H264 PEQUENO: " +
                                        inputBuffer.remaining() +
                                        " < " +
                                        nv12.length
                        );

                        codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                image
                                        .getImageInfo()
                                        .getTimestamp()
                                        / 1000L,
                                0
                        );
                    }
                }
            }

            // ====================================================
            // SAIDA
            // MediaCodec -> H264 -> TCP
            // ====================================================

            MediaCodec.BufferInfo info =
                    new MediaCodec.BufferInfo();

            while (true) {

                int outputIndex =
                        codec.dequeueOutputBuffer(
                                info,
                                0
                        );

                if (outputIndex >= 0) {

                    if (info.size > 0) {

                        ByteBuffer outputBuffer =
                                codec.getOutputBuffer(
                                        outputIndex
                                );

                        if (outputBuffer != null) {

                            outputBuffer.position(
                                    info.offset
                            );

                            outputBuffer.limit(
                                    info.offset +
                                    info.size
                            );

                            byte[] h264 =
                                    new byte[
                                            info.size
                                    ];

                            outputBuffer.get(
                                    h264
                            );

                            // SPS/PPS ja foram enviados via
                            // INFO_OUTPUT_FORMAT_CHANGED.
                            // Enviar tambem o buffer COC_CONFIG
                            // duplicaria o cabecalho no stream.
                            if (
                                    (
                                            info.flags &
                                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                                    ) != 0
                            ) {

                                Log.i(
                                        "LUCAO_TCP",
                                        "BUFFER COC_CONFIG IGNORADO: " +
                                                info.size +
                                                " bytes"
                                );

                            } else {

                                ExecutorService executor =
            videoWriterExecutor;

    if (executor != null) {
        executor.execute(() -> {

            OutputStream output =
                    videoOutput;

            if (output == null) {
                return;
            }

            try {

                output.write(h264);

                ultimaEscritaVideo =
                        System.currentTimeMillis();

            } catch (IOException e) {

                Log.e(
                        "LUCAO_TCP",
                        "CONEXAO H264 PERDIDA: " +
                                e.getMessage()
                );

                desconectarVideoTcp();

                iniciarReconexaoVideo();
            }
        });
    }

                                h264FramesSaida++;

                                h264BytesSaida +=
                                        info.size;
                            }
                        }
                    }

                    codec.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                } else if (
                        outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                ) {

                    MediaFormat outputFormat =
                            codec.getOutputFormat();

                    Log.i(
                            "LUCAO_H264",
                            "FORMATO H264: " +
                                    outputFormat
                    );

                    OutputStream output =
                            videoOutput;

                    if (output != null) {

                        ByteBuffer sps =
                                outputFormat
                                        .getByteBuffer(
                                                "csd-0"
                                        );

                        ByteBuffer pps =
                                outputFormat
                                        .getByteBuffer(
                                                "csd-1"
                                        );

                        if (sps != null) {

    ByteBuffer copiaSps =
            sps.duplicate();

    copiaSps.position(0);

    byte[] dadosSps =
            new byte[
                    copiaSps.remaining()
            ];

    copiaSps.get(
            dadosSps
    );

    h264Sps = dadosSps;

    output.write(
            dadosSps
    );

    Log.i(
            "LUCAO_TCP",
            "SPS ENVIADO E ARMAZENADO: " +
                    dadosSps.length +
                    " bytes"
    );
}

                        if (pps != null) {

    ByteBuffer copiaPps =
            pps.duplicate();

    copiaPps.position(0);

    byte[] dadosPps =
            new byte[
                    copiaPps.remaining()
            ];

    copiaPps.get(
            dadosPps
    );

    h264Pps = dadosPps;

    output.write(
            dadosPps
    );

    Log.i(
            "LUCAO_TCP",
            "PPS ENVIADO E ARMAZENADO: " +
                    dadosPps.length +
                    " bytes"
    );
}
                    }

                } else {

                    break;
                }
            }

            // ====================================================
            // ESTATISTICAS
            // ====================================================

            long agora =
                    System.currentTimeMillis();

            if (h264JanelaInicioMs == 0) {
                h264JanelaInicioMs =
                        agora;
            }

            long intervalo =
                    agora -
                    h264JanelaInicioMs;

            if (intervalo >= 1000) {

                double fpsEntrada =
                        h264FramesEntrada *
                        1000.0 /
                        intervalo;

                double fpsSaida =
                        h264FramesSaida *
                        1000.0 /
                        intervalo;

                // Usado pelo painel do PC (/api/status).
                fpsAtual = fpsSaida;

                kbpsAtual =
                        h264BytesSaida * 8.0 / intervalo;

                Log.i(
                        "LUCAO_H264",
                        String.format(
                                java.util.Locale.US,
                                "CONTINUO: entrada=%.1f FPS saida=%.1f FPS bytes=%d",
                                fpsEntrada,
                                fpsSaida,
                                h264BytesSaida
                        )
                );

                h264FramesEntrada = 0;
                h264FramesSaida = 0;
                h264BytesSaida = 0;

                h264JanelaInicioMs =
                        agora;
            }

        } catch (Exception e) {

            Log.e(
                    "LUCAO_H264",
                    "ERRO NO H264 CONTINUO",
                    e
            );
        }
    }

    // ============================================================
    // ENCODER H264
    // ============================================================

    private synchronized void prepararEncoderH264() {

        if (h264Encoder != null) {

            Log.i(
                    "LUCAO_H264",
                    "ENCODER JA INICIADO"
            );

            return;
        }

        MediaCodec codec = null;

        try {

            MediaFormat format =
                    MediaFormat.createVideoFormat(
                            MediaFormat.MIMETYPE_VIDEO_AVC,
                            VIDEO_WIDTH,
                            VIDEO_HEIGHT
                    );

            format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo
                            .CodecCapabilities
                            .COLOR_FormatYUV420SemiPlanar
            );

            format.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    VIDEO_BITRATE
            );

            format.setInteger(
                    MediaFormat.KEY_FRAME_RATE,
                    VIDEO_FPS
            );

            format.setInteger(
                    MediaFormat.KEY_LATENCY,
                    1
            );

            format.setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    1
            );

            codec =
                    MediaCodec.createEncoderByType(
                            MediaFormat.MIMETYPE_VIDEO_AVC
                    );

            Log.i(
                    "LUCAO_CODEC",
                    "ENCODER H264 SELECIONADO: " +
                            codec.getName()
            );

            codec.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            codec.start();

            h264Encoder =
                    codec;

            Log.i(
                    "LUCAO_H264",
                    "ENCODER INICIADO: " +
                            VIDEO_WIDTH +
                            "x" +
                            VIDEO_HEIGHT +
                            " @" +
                            VIDEO_FPS +
                            " FPS / " +
                            VIDEO_BITRATE +
                            " bps"
            );

        } catch (Exception e) {

            Log.e(
                    "LUCAO_H264",
                    "ERRO AO INICIAR H264",
                    e
            );

            if (codec != null) {

                try {
                    codec.release();
                } catch (
                        Exception ignored
                ) {
                }
            }

            h264Encoder = null;
        }
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================

    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onDestroy() {

        servicoAtivo = false;

        if (lifecycleRegistry != null) {

            lifecycleRegistry.setCurrentState(
                    Lifecycle.State.DESTROYED
            );
        }

        if (h264Encoder != null) {

            try {
                h264Encoder.stop();
            } catch (Exception ignored) {
            }

            try {
                h264Encoder.release();
            } catch (Exception ignored) {
            }

            h264Encoder = null;
        }

        if (videoOutput != null) {

            try {
                videoOutput.close();
            } catch (Exception ignored) {
            }

            videoOutput = null;
        }

        if (videoSocket != null) {

            try {
                videoSocket.close();
            } catch (Exception ignored) {
            }

            videoSocket = null;
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        if (networkExecutor != null) {
            networkExecutor.shutdown();
        }
        if (videoWriterExecutor != null) {
    videoWriterExecutor.shutdown();
}

        super.onDestroy();
    }

    // ============================================================
    // BINDER
    // ============================================================

    @Nullable
    @Override
public int onStartCommand(
        Intent intent,
        int flags,
        int startId
) {

    if (intent != null) {

        String action = intent.getAction();

        if (ACTION_SET_ZOOM.equals(action)) {

            float zoom =
                    intent.getFloatExtra(
                            "zoom",
                            0.0f
                    );

            zoom = Math.max(
                    0.0f,
                    Math.min(1.0f, zoom)
            );

            aplicarZoomCamera(zoom);

        } else if (ACTION_SET_CAMERA_ACTIVE.equals(action)) {

            boolean ativo =
                    intent.getBooleanExtra(
                            "ativo",
                            true
                    );

            definirWebcamAtiva(ativo);

        } else if (ACTION_SET_HOST.equals(action)) {

            String host =
                    intent.getStringExtra("host");

            if (host != null) {

                host = host.trim();

                pcHost = host;

                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_PC_HOST, host)
                        .apply();

                Log.i(
                        "LUCAO_CONTROL",
                        "PC HOST DEFINIDO: " + host
                );

                // Sem USB, a proxima tentativa de reconexao
                // ja usa o novo host.
                if (videoOutput == null) {
                    iniciarReconexaoVideo();
                }
            }

        } else if (ACTION_START_CAMERA.equals(action)) {

            // Permissao de camera acabou de ser concedida
            // (ou a Activity quer reiniciar a camera).
            ContextCompat
                    .getMainExecutor(this)
                    .execute(this::iniciarCamera);

        } else if (ACTION_REQUEST_STATUS.equals(action)) {

            broadcastStatus();
        }
    }

    return START_STICKY;
}

// ============================================================
// BINDER
// ============================================================

@Nullable
@Override
public IBinder onBind(
        Intent intent
) {
    return null;
}

// ============================================================
    // POLLING DE CONTROLE (HTTP 5050)
    // ============================================================

    private void iniciarPollingControle() {

        networkExecutor.execute(() -> {

            Log.i(
                    "LUCAO_CONTROL",
                    "Iniciando polling de controle (" +
                            deviceId +
                            ")"
            );

            int falhasPolling = 0;

            while (servicoAtivo) {

                try {

                    Thread.sleep(150);

                    // Sem video conectado nao ha painel esperando
                    // comandos deste aparelho.
                    if (!pcConectado) {
                        continue;
                    }

                    String resposta = requisicaoHttp(
                            "GET",
                            hostAtivo,
                            "/api/poll?id=" + deviceId,
                            null
                    );

                    falhasPolling = 0;

                    aplicarComandoRemoto(resposta);

                } catch (Exception e) {

                    falhasPolling++;

                    if (falhasPolling % 100 == 1) {

                        Log.w(
                                "LUCAO_CONTROL",
                                "POLLING FALHOU (" +
                                        falhasPolling +
                                        "): " +
                                        e.getMessage()
                        );
                    }

                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        });
    }

    private void aplicarComandoRemoto(String resposta) {

        if (resposta == null || resposta.isEmpty()) {
            return;
        }

        try {

            JSONObject json = new JSONObject(resposta);

            if (json.isNull("comando")) {
                return;
            }

            JSONObject comando = json.optJSONObject("comando");

            if (comando == null) {
                return;
            }

            String tipo = comando.optString("tipo", "");

            if ("zoom".equals(tipo)) {

                float zoom = (float) comando.optDouble("valor", 0.0);

                aplicarZoomCamera(
                        Math.max(0.0f, Math.min(1.0f, zoom))
                );

            } else if ("webcam".equals(tipo)) {

                definirWebcamAtiva(
                        comando.optBoolean("ligada", true)
                );

            } else if ("reconectar".equals(tipo)) {

                Log.i(
                        "LUCAO_CONTROL",
                        "RECONEXAO DE VIDEO PEDIDA PELO PC"
                );

                desconectarVideoTcp();
                iniciarReconexaoVideo();
            }

        } catch (Exception e) {

            Log.w(
                    "LUCAO_CONTROL",
                    "COMANDO INVALIDO: " + e.getMessage()
            );
        }
    }

    private void definirWebcamAtiva(boolean ativo) {

        if (cameraAtiva == ativo) {
            return;
        }

        cameraAtiva = ativo;

        if (ativo) {
            solicitarKeyframeH264 = true;

            if (videoOutput == null) {
                iniciarReconexaoVideo();
            }
        } else {
            // Sem frames, as estatisticas antigas ficariam
            // congeladas no painel.
            fpsAtual = 0;
            kbpsAtual = 0;
        }

        Log.i(
                "LUCAO_CONTROL",
                "WEBCAM " + (ativo ? "ON" : "OFF")
        );

        // Mantem o checkbox da MainActivity em sincronia.
        broadcastStatus();
    }

    private void aplicarZoomCamera(float zoom) {
        // Envia o comando para a Thread Principal do Android (seguranÃ§a do CameraX)
        ContextCompat.getMainExecutor(this).execute(() -> {
            if (cameraAtual != null) {
                try {
                    cameraAtual.getCameraControl().setLinearZoom(zoom);
                    Log.i("LUCAO_CONTROL", "Zoom aplicado via PC: " + zoom);
                } catch (Exception e) {
                    Log.e("LUCAO_CONTROL", "Erro ao aplicar zoom via PC", e);
                }
            } else {
                Log.w("LUCAO_CONTROL", "Zoom ignorado - cameraAtual ainda Ã© nula");
            }
        });
    }

}
