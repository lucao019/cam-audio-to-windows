package com.lucao.link;

import org.json.JSONArray;
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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.util.Range;

import android.graphics.ImageFormat;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import android.hardware.camera2.CaptureRequest;
import androidx.camera.camera2.interop.Camera2CameraInfo;
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

    // Resolucao roda no MAXIMO disponivel da camera (resolvida em
    // runtime no iniciarCamera()). Estes defaults so valem ate a
    // camera ser aberta; depois sao sobrescritos pelo maior Size
    // exposto pelo CameraX.
    private static volatile int VIDEO_WIDTH = 640;
    private static volatile int VIDEO_HEIGHT = 480;
    // Resolucao que a CAMERA entrega. Normalmente a mesma do
    // encoder, mas pode ser menor: o sensor so roda a 30 FPS nas
    // dimensoes nativas (ex.: 1920x1080), enquanto o encoder H.264
    // exige multiplo de 16 (1920x1088). Nesse caso a camera entrega
    // 1080 e o NV12 e preenchido ate 1088 em converterParaNV12().
    private static volatile int CAMERA_WIDTH = 640;
    private static volatile int CAMERA_HEIGHT = 480;
    private volatile long ultimoAvisoResolucao = 0;

    // Diagnostico de geometria dos planos YUV (log uma vez).
    private int ultimoCapacidadeUBuffer = -1;
    private int ultimoURowStride = -1;

    // Buffer reaproveitado para copiar uma linha de chroma por vez
    // em converterParaNV12(). Evita realocacao a cada frame.
    private byte[] linhasUv;

    private static final int VIDEO_FPS = 30;
    // Teto de bitrate do encoder H.264. Vale so dentro do USB
    // (adb reverse): 40 Mbps cabe folgado num link USB 2.0 e deixa
    // a imagem do 1080p sem artefato de compressao. Por Wi-Fi o
    // encoder continua com o mesmo teto, mas o link e que manda.
    private static final int VIDEO_BITRATE = 40_000_000;

    // Host 127.0.0.1 = modo dedicado USB via ADB Reverse
    // (companion.js monta os tuneis tcp:5050 e tcp:5051).
    //
    // Se o USB nao estiver ativo, o servico tenta o host Wi-Fi
    // salvo (pcHost), preenchido pela MainActivity/companion.
    private static final String USB_HOST = "127.0.0.1";
    private static final int VIDEO_PORT = 5051;
    private static final int AUDIO_PORT = 5054;
    private static final int CONTROL_PORT = 5050;

    // Descoberta na LAN: o celular faz broadcast e o servidor do PC
    // responde com o proprio IP. Assim o usuario nao digita nada.
    private static final int DISCOVERY_PORT = 5053;
    private static final String DISCOVERY_PING = "LUCAO?1";
    private static final String DISCOVERY_RESPOSTA = "LUCAO!1";
    private static final int DISCOVERY_TIMEOUT_MS = 1000;
    private static final int DISCOVERY_INTERVALO_MS = 2000;

    // ============================================================
    // IDENTIDADE / PREFERENCIAS
    // ============================================================

    private static final String PREFS = "lucao_link";
    private static final String PREF_DEVICE_ID = "deviceId";
    private static final String PREF_DEVICE_NAME = "deviceName";
    private static final String PREF_PC_HOST = "pcHost";
    private static final String PREF_LENTE = "lente";

    private volatile String deviceId = "";
    private volatile String deviceName = "android";
    private volatile String pcHost = "";

    // Preenchido pelo loop de descoberta quando o PC responde na LAN.
    // Entra em candidatosHost() entre o USB e o pcHost manual.
    private volatile String hostDescoberto = "";
    private Thread threadDescoberta = null;
    private volatile boolean descobertaAtiva = false;

    private volatile String hostAtivo = USB_HOST;
    private volatile String transporteAtivo = "usb";

    private volatile double fpsAtual = 0;
    private volatile double kbpsAtual = 0;

    // Lente em uso: "back" ou "front". Persistida em prefs para
    // sobreviver ao reinicio do servico.
    private volatile String lenteAtual = "back";
    // Lentes que o aparelho realmente expoe, enviadas ao PC.
    private volatile List<String> lentesDisponiveis =
            new ArrayList<>();

    // Lanterna (torch) ligada/desligada pelo PC.
    private volatile boolean flashLigado = false;
    private volatile boolean flashDisponivel = false;

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
    public static final String ACTION_SET_LENTE =
            "com.lucao.link.ACTION_SET_LENTE";

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

    // Fila limitada do escritor de video. O writer antigo era um
    // single-thread executor SEM limite: se o Wi-Fi engasgava, cada
    // frame virava uma task enfileirada e o atraso crescia sem
    // voltar. Agora o produtor descarta o frame mais antigo quando
    // a fila enche, preferindo pular frames a acumular delay.
    private static final int VIDEO_WRITE_MAX_RENDEZ =
            Integer.getInteger("lucao.videoFila", 4);
    private java.util.concurrent.ArrayBlockingQueue<byte[]>
            videoWriteQueue;
    private Thread videoWriterThread;
    private volatile boolean videoWriterAtivo = false;

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

    // Resolucao REAL entregue pelo sensor (aprendida do primeiro
    // frame). O encoder e recriado nela se diferir do que o HAL
    // anunciou. 0 = ainda nao aprendida.
    private volatile int resolucaoRealLargura = 0;
    private volatile int resolucaoRealAltura = 0;

    // ============================================================
    // SERVICE
    // ============================================================

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(
                "LUCAO_SERVICE",
                "CameraForegroundService.onCreate()"
        );

        carregarIdentidade();

        iniciarDescoberta();

        listarFpsCamera();

        cameraExecutor =
                Executors.newSingleThreadExecutor();

        networkExecutor =
                Executors.newFixedThreadPool(4);

        iniciarEscritorVideo();

        // Lifecycle usado pelo CameraX
        lifecycleRegistry =
                new LifecycleRegistry(this);

        lifecycleRegistry.setCurrentState(
                Lifecycle.State.CREATED
        );

        // Foreground Service precisa da notificacao
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

        // Agora o Service esta ativo para o CameraX
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

        String lente = prefs.getString(PREF_LENTE, "back");

        lenteAtual = "front".equals(lente) ? "front" : "back";
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

    // ============================================================
    // DESCOBERTA NA LAN (UDP)
    // ============================================================

    /**
     * Sobe o loop de descoberta. Se o USB ja resolver, o loop
     * para sozinho; senao ele segue tentando ate achar o PC.
     */
    private void iniciarDescoberta() {

        if (descobertaAtiva) {
            return;
        }

        descobertaAtiva = true;

        threadDescoberta = new Thread(() -> {

            while (descobertaAtiva) {

                // USB ativo dispensa a busca
                if (pcConectado && USB_HOST.equals(hostAtivo)) {
                    break;
                }

                String achado = procurarPc();

                if (achado != null && !achado.isEmpty()) {

                    Log.i(
                            "LUCAO_SERVICE",
                            "PC DESCOBERTO NA LAN: " + achado
                    );

                    hostDescoberto = achado;

                    // Reflete no campo editavel como fallback
                    pcHost = achado;

                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(PREF_PC_HOST, achado)
                            .apply();

                    // Sem USB, a proxima tentativa de reconexao
                    // ja usa o host descoberto.
                    if (videoOutput == null) {
                        iniciarReconexaoVideo();
                    }

                    break;
                }

                dormir(DISCOVERY_INTERVALO_MS);
            }

            descobertaAtiva = false;
        });

        threadDescoberta.setDaemon(true);
        threadDescoberta.start();
    }

    private void pararDescoberta() {

        descobertaAtiva = false;

        if (threadDescoberta != null) {
            threadDescoberta.interrupt();
            threadDescoberta = null;
        }
    }

    /**
     * Envia o broadcast e espera a resposta do PC.
     * Devolve o IP informado ou "" se ninguem responder.
     */
    private String procurarPc() {

        DatagramSocket socket = null;

        try {

            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(DISCOVERY_TIMEOUT_MS);

            byte[] ping = DISCOVERY_PING.getBytes("UTF-8");

            DatagramPacket pacote = new DatagramPacket(
                    ping,
                    ping.length,
                    InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_PORT
            );

            socket.send(pacote);

            byte[] buffer = new byte[256];

            DatagramPacket resposta = new DatagramPacket(
                    buffer,
                    buffer.length
            );

            socket.receive(resposta);

            String texto = new String(
                    resposta.getData(),
                    0,
                    resposta.getLength(),
                    "UTF-8"
            ).trim();

            String[] partes = texto.split("\\s+");

            // Formato esperado: LUCAO!1 <nome> <ip>
            if (partes.length >= 3 &&
                    DISCOVERY_RESPOSTA.equals(partes[0])) {

                return partes[partes.length - 1];
            }

            return "";

        } catch (SocketTimeoutException e) {

            // Timeout e o caso normal quando o PC ainda nao subiu
            return "";

        } catch (SocketException e) {

            // receive() estoura SocketException quando o socket e
            // fechado. Preserva o flag de interrupcao para o loop
            // encerrar sem esperar o proximo ciclo.
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }

            return "";

        } catch (Exception e) {

            Log.w(
                    "LUCAO_SERVICE",
                    "Falha na descoberta UDP: " + e
            );

            return "";

        } finally {

            if (socket != null) {
                socket.close();
            }
        }
    }

    private void dormir(long ms) {

        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * USB (127.0.0.1 via ADB reverse) tem prioridade; depois o
     * host descoberto na LAN e, por fim, o host Wi-Fi informado
     * pela MainActivity/companion.
     */
    private List<String> candidatosHost() {

        List<String> lista = new ArrayList<>();

        lista.add(USB_HOST);

        String descoberto = hostDescoberto == null
                ? ""
                : hostDescoberto.trim();

        if (!descoberto.isEmpty() && !lista.contains(descoberto)) {
            lista.add(descoberto);
        }

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
            json.put("lente", lenteAtual);
            json.put("flash", flashLigado);
            json.put("flashDisponivel", flashDisponivel);

            JSONArray lentes = new JSONArray();

            for (String l : lentesDisponiveis) {
                lentes.put(l);
            }

            json.put("lentesDisponiveis", lentes);

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

        List<String> lentes = new ArrayList<>();

        for (String cameraId : cameraManager.getCameraIdList()) {

            android.hardware.camera2.CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);

            Integer facing =
                    characteristics.get(
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING
                    );

            // Detecta quais lentes (frontal/traseira) o aparelho expoe.
            if (
                    facing != null &&
                    facing ==
                            android.hardware.camera2.CameraCharacteristics
                                    .LENS_FACING_FRONT &&
                    !lentes.contains("front")
            ) {
                lentes.add("front");
            }

            if (
                    facing != null &&
                    facing ==
                            android.hardware.camera2.CameraCharacteristics
                                    .LENS_FACING_BACK &&
                    !lentes.contains("back")
            ) {
                lentes.add("back");
            }

            if (
                    facing != null &&
                    facing ==
                            android.hardware.camera2.CameraCharacteristics
                                    .LENS_FACING_BACK
            ) {

                // So a lente traseira costuma ter lanterna.
                Boolean temFlash =
                        characteristics.get(
                                android.hardware.camera2.CameraCharacteristics
                                        .FLASH_INFO_AVAILABLE
                        );

                flashDisponivel =
                        temFlash != null && temFlash;

                Log.i(
                        "LUCAO_CONTROL",
                        "FLASH DISPONIVEL: " + flashDisponivel
                );

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

        lentesDisponiveis = lentes;

        // Se a lente persistida nao existe neste aparelho, cai para a
        // traseira.
        if (!lentes.isEmpty() && !lentes.contains(lenteAtual)) {
            lenteAtual = lentes.contains("back") ? "back" : lentes.get(0);
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
private void resolverMaiorResolucao(
        CameraSelector selector
) {

    try {

        if (cameraProvider == null) {

            // Pode ser chamado antes do listener da câmera rodar:
            // espera o provider ficar pronto (curto, normalmente ja
            // esta inicializado quando o servico esta de pe).
            cameraProvider =
                    ProcessCameraProvider
                            .getInstance(this)
                            .get();
        }

        if (cameraProvider == null) {
            return;
        }

        // CameraInfo da lente escolhida -> Camera2CameraInfo -> a
        // StreamConfigurationMap crua, que lista todas as resolucoes
        // que a lente expoe para analise (YUV_420_888).
        CameraInfo info =
                cameraProvider
                        .getCameraInfo(
                                selector
                        );

        if (info == null) {
            return;
        }

        android.hardware.camera2.params.StreamConfigurationMap mapa =
                Camera2CameraInfo
                        .from(info)
                        .getCameraCharacteristic(
                                android.hardware.camera2.CameraCharacteristics
                                        .SCALER_STREAM_CONFIGURATION_MAP
                        );

        if (mapa == null) {
            return;
        }

        android.util.Size[] suportados =
                mapa.getOutputSizes(
                        ImageFormat.YUV_420_888
                );

        if (suportados == null || suportados.length == 0) {
            return;
        }

        List<android.util.Size> tamanhos =
                new java.util.ArrayList<>(
                        java.util.Arrays.asList(suportados)
                );

        // Se o encoder JA esta de pe, a resolucao dele esta travada:
        // NAO mexer. (Mudar VIDEO_WIDTH/HEIGHT aqui deixaria o
        // encoder defasado em relacao a camera.)
        if (h264Encoder != null) {
            Log.i(
                    "LUCAO_CAMERA",
                    "ENCODER ATIVO EM " + VIDEO_WIDTH + "x" +
                            VIDEO_HEIGHT + " - NAO RECALCULAR"
            );
            return;
        }

        // A camera expoe resolucoes que o encoder de H.264 pode
        // NAO aceitar (ex.: 3264x2448 na moto g22 -> o MediaCodec
        // joga IllegalArgumentException no configure). Entao o
        // "maximo de verdade" e a maior resolucao que os DOIS
        // suportam. O encoder so aceita dimensoes multiplas de 16
        // (p.ex. 1920x1080 -> 1920x1088).
        List<android.util.Size> suportadasPeloEncoder =
                tamanhosSuportadosPeloEncoderH264();

        android.util.Size maior = null;

        for (android.util.Size s : tamanhos) {

            // Na imagem real o encoder so aceita 1920x1088, mesmo
            // que a camera exponha 1920x1080. Compara o tamanho
            // (largura, altura) OU a versao "arredondada pra 16".
            boolean cabe =
                    suportadasPeloEncoder.isEmpty() ||
                    suportadasPeloEncoder.contains(s) ||
                    suportadasPeloEncoder.contains(
                            new android.util.Size(
                                    arredondarPara16(s.getWidth()),
                                    arredondarPara16(s.getHeight())
                            )
                    );

            if (cabe == false) {
                continue;
            }

            if (maior == null) {
                maior = s;
                continue;
            }

            // Preferencia: se os dois viram o MESMO tamanho de
            // encoder (ex.: 1920x1080 e 1920x1088 viram 1920x1088),
            // fica com o MENOR nativo. O moto g22 roda 1920x1088 a
            // ~6 FPS e 1920x1080 a 30 FPS.
            boolean mesmoEncoder =
                    arredondarPara16(s.getWidth()) ==
                            arredondarPara16(maior.getWidth()) &&
                    arredondarPara16(s.getHeight()) ==
                            arredondarPara16(maior.getHeight());

            if (mesmoEncoder) {

                long area = (long) s.getWidth() * s.getHeight();
                long areaMaior =
                        (long) maior.getWidth() * maior.getHeight();

                if (area < areaMaior) {
                    maior = s;
                }

                continue;
            }

            long area = (long) s.getWidth() * s.getHeight();
            long areaMaior =
                    (long) maior.getWidth() * maior.getHeight();

            if (area > areaMaior) {
                maior = s;
            }
        }

        if (maior == null) {
            return;
        }

        // O encoder recebe dimensoes multiplas de 16 (1920x1080 ->
        // 1920x1088). A camera, quando existe esse passo de 8 linhas,
        // entrega o tamanho NATIVO (1920x1080): o sensor so roda a
        // 30 FPS nas dimensoes nativas; em 1088 ele cai para ~6 FPS.
        VIDEO_WIDTH = arredondarPara16(maior.getWidth());
        VIDEO_HEIGHT = arredondarPara16(maior.getHeight());

        // CAMERA_WIDTH/HEIGHT = dimensoes NATIVAS do sensor (o que
        // a camera realmente entrega). O encoder usa o multiplo de
        // 16 de VIDEO_WIDTH/HEIGHT.
        //
        // ATENCAO: estas dimensoes sao apenas o PONTO DE PARTIDA.
        // O analyzer aprende a resolucao REAL do primeiro frame em
        // garantirEncoderParaFrame() e recria o encoder nela se
        // diferir. Isto e obrigatorio: o HAL do moto g22 anuncia
        // sizes que ele nao entrega de fato (ex.: pedimos 854x480 e
        // ele manda 960x540), e qualquer mismatch fazia o frame
        // guard descartar ~24 de 30 frames/s -> travava em 6 FPS.
        CAMERA_WIDTH = maior.getWidth();
        CAMERA_HEIGHT = maior.getHeight();

        Log.i(
                "LUCAO_CAMERA",
                "RESOLUCAO MAXIMA ESCOLHIDA: camera=" +
                        CAMERA_WIDTH + "x" + CAMERA_HEIGHT +
                        " encoder=" + VIDEO_WIDTH + "x" + VIDEO_HEIGHT +
                        " (camera=" + tamanhos.size() +
                        " encoder=" + suportadasPeloEncoder.size() +
                        " opcoes)"
        );

    } catch (Exception e) {

        Log.e(
                "LUCAO_CAMERA",
                "ERRO AO RESOLVER RESOLUCAO MAXIMA",
                e
        );
    }
}

// Arredonda uma dimensao para o proximo multiplo de 16 (o
// encoder H.264 exige isso: 1080 -> 1088, 2448 -> 2448).
private int arredondarPara16(int valor) {
    return ((valor + 15) / 16) * 16;
}

// Lista as resolucoes que o encoder H.264 do aparelho aceita de
// verdade. Usa o MediaCodecInfo do encoder AVC escolhido e cruza
// com o VideoCapabilities (dimensoes + taxa de quadros). Serve
// para nao escolher uma resolucao que a camera expoe mas o
// encoder rejeita no configure().
private List<android.util.Size> tamanhosSuportadosPeloEncoderH264() {

    List<android.util.Size> vazio =
            new java.util.ArrayList<>();

    try {

        android.media.MediaCodecList lista =
                new android.media.MediaCodecList(
                        android.media.MediaCodecList.REGULAR_CODECS
                );

        for (android.media.MediaCodecInfo info : lista.getCodecInfos()) {

            if (info.isEncoder() == false) {
                continue;
            }

            boolean avc = false;

            for (String tipo : info.getSupportedTypes()) {
                if ("video/avc".equalsIgnoreCase(tipo)) {
                    avc = true;
                    break;
                }
            }

            if (avc == false) {
                continue;
            }

            android.media.MediaCodecInfo.CodecCapabilities caps =
                    info.getCapabilitiesForType("video/avc");

            if (caps == null || caps.getVideoCapabilities() == null) {
                continue;
            }

            android.util.Range<Integer> largura =
                    caps.getVideoCapabilities().getSupportedWidths();

            android.util.Range<Integer> altura =
                    caps.getVideoCapabilities().getSupportedHeights();

            if (largura == null || altura == null) {
                continue;
            }

            List<android.util.Size> achados =
                    new java.util.ArrayList<>();

            // Varre passos de 16 (alinhamento tipico dos encoders)
            // para montar o conjunto de tamanhos aceitos.
            for (int w = largura.getLower(); w <= largura.getUpper(); w += 16) {

                for (int h = altura.getLower(); h <= altura.getUpper(); h += 16) {

                    if (
                            caps.getVideoCapabilities()
                                    .isSizeSupported(w, h)
                    ) {
                        achados.add(new android.util.Size(w, h));
                    }
                }
            }

            if (achados.size() > vazio.size()) {
                vazio = achados;
            }
        }

    } catch (Exception e) {

        Log.e(
                "LUCAO_H264",
                "ERRO AO LISTAR RESOLUCOES DO ENCODER",
                e
        );
    }

    return vazio;
}

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

            // Alvo na resolucao NATIVA que o HAL do moto g22
            // declara rodar a 30 FPS em YUV_420_888.
            //
            // O tamanho pedido aqui e apenas uma SUGESTAO: o HAL
            // pode entregar o Size suportado mais proximo (ex.:
            // pedir 854x480 e receber 960x540). O guard adaptativo
            // em processarFrameH264() aprende o tamanho REAL do
            // primeiro frame e recria o encoder nele, entao nao ha
            // mais descarte por mismatch.
            android.util.Size alvoResolucao =
                    new android.util.Size(CAMERA_WIDTH, CAMERA_HEIGHT);

            // 16:9 OBRIGATORIO: sem isso a camera prefere 4:3 e
            // entrega 1920x1440 (altura que o encoder nao aceita).
            ResolutionSelector resolutionSelector =
                    new ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                    AspectRatioStrategy
                                            .RATIO_16_9_FALLBACK_AUTO_STRATEGY
                            )
                            .setResolutionStrategy(
                                    new ResolutionStrategy(
                                            alvoResolucao,
                                            ResolutionStrategy
                                                    .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                            )
                            .build();

            CameraSelector cameraSelector =
                    "front".equals(lenteAtual)
                            ? CameraSelector.DEFAULT_FRONT_CAMERA
                            : CameraSelector.DEFAULT_BACK_CAMERA;

            ImageAnalysis.Builder imageAnalysisBuilder =
        new ImageAnalysis.Builder()
                .setResolutionSelector(
                        resolutionSelector
                )
                // FORMATO YUV EXPLICITO: sem isto o CameraX entrega
                // um buffer OPAQUE/PRIVATE (0x23) em 1080p e o sensor
                // cai para ~6 FPS no MTK. O HAL declara suporte a
                // [33 1920 1080 33333333] = YUV_420_888 1080p @30fps;
                // pedir o formato YUV explicitamente forca esse modo.
                .setOutputImageFormat(
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
                )
                .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                );

// FAIXA FIXA [30,30] - NAO usar [5,30].
//
// O sensor do moto g22 declara exatamente estas faixas de
// CONTROL_AE_TARGET_FPS_RANGE: [10,10] [15,15] [15,20] [20,20]
// [5,30] [30,30]. Com [5,30] o AE tem liberdade para escolher
// QUALQUER fps de 5 a 30 e, num ambiente sem muita luz, a
// prioridade do AE e a exposicao -> ele escolhe o minimo. Para
// camera de monitoramento, framerate estavel vale mais do que
// cena escura sem ruido, entao travamos em 30.
//
// NOTA: o "6 FPS" que aparecia no log CONTINUO NAO era o AE (nem
// o encoder). Era o frame guard descartando quase todo frame por
// mismatch de resolucao (o HAL anuncia 854x480 mas o sensor
// entrega 960x540). Isso esta corrigido no guard adaptativo de
// processarFrameH264().
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

            // Preview local opcional (so quando a Activity esta
            // visivel e entregou uma SurfaceProvider).
            Preview.SurfaceProvider provider = previewProvider;

            Preview preview = null;

            if (provider != null) {

                // Preview LOCAL em resolucao pequena de proposito:
                // um segundo stream 1080p ao lado do ImageAnalysis
                // 1080p estoura o conjunto de streams do moto g22 e
                // derruba TUDO para ~6 FPS. O preview e so o
                // enquadramento na tela; 720p basta.
                android.util.Size alvoPreview =
                        new android.util.Size(1280, 720);

                ResolutionSelector selectorPreview =
                        new ResolutionSelector.Builder()
                                .setAspectRatioStrategy(
                                        AspectRatioStrategy
                                                .RATIO_16_9_FALLBACK_AUTO_STRATEGY
                                )
                                .setResolutionStrategy(
                                        new ResolutionStrategy(
                                                alvoPreview,
                                                ResolutionStrategy
                                                        .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                        )
                                )
                                .build();

                preview = new Preview.Builder()
                        .setResolutionSelector(selectorPreview)
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

        // O buffer tem o tamanho do ENCODER (multiplo de 16), que
        // pode ter mais linhas que a camera entrega (ex.: camera
        // 1920x1080 -> encoder 1920x1088). As linhas que sobram sao
        // preenchidas com o ultimo valor valido (padding neutro).
        int encWidth = VIDEO_WIDTH;
        int encHeight = VIDEO_HEIGHT;

        ImageProxy.PlaneProxy[] planes =
                image.getPlanes();

        byte[] nv12 =
                new byte[
                        encWidth * encHeight * 3 / 2
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
        // PADDING Y (apenas linhas extras)
        // ----------------------------

        // Replica a ultima linha ate a altura do encoder. Na pratica
        // sao poucas linhas (1080 -> 1088).
        if (encHeight > height) {

            int inicioUltimaLinha =
                    (height - 1) * width;

            int fimUltimaLinha =
                    inicioUltimaLinha + width;

            for (
                    int row = height;
                    row < encHeight;
                    row++
            ) {

                int destino =
                        row * width;

                System.arraycopy(
                        nv12,
                        inicioUltimaLinha,
                        nv12,
                        destino,
                        width
                );
            }

            pos = encHeight * width;
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

        // DIAGNOSTICO (uma vez por resolucao): imprime a geometria
        // real dos planos para confirmar o layout NV21.
        if (
                uBuffer.capacity() != ultimoCapacidadeUBuffer ||
                uRowStride != ultimoURowStride
        ) {

            ultimoCapacidadeUBuffer =
                    uBuffer.capacity();

            ultimoURowStride =
                    uRowStride;

            android.util.Log.i(
                    "LUCAO_H264",
                    "PLANOS: " +
                            width + "x" + height +
                            " | u: cap=" + uBuffer.capacity() +
                            " lim=" + uBuffer.limit() +
                            " rowStride=" + uRowStride +
                            " pxStride=" + uPixelStride +
                            " base=" + uBase +
                            " | v: cap=" + vBuffer.capacity() +
                            " rowStride=" + vRowStride +
                            " pxStride=" + vPixelStride +
                            " base=" + vBase
            );
        }

        // CAMINHO RAPIDO (NV12 / YUV_420_888 semi-planar): a ROM
        // entrega o chroma INTERCALADO num unico buffer com a ordem
        // que o NV12 espera (U0 V0 U1 V1 ...), uPixelStride==2,
        // vPixelStride==2 (o "v" aponta 1 byte depois do "u" dentro
        // do mesmo buffer intercalado).
        //
        // DIAGNOSTICO que confirmou a ordem (moto g22, 1920x1080):
        // lendo uBuffer e vBuffer no MESMO offset fisico os valores
        // sao ~iguais (u=127 v=124), ou seja sao os MESMOS bytes do
        // par U/V lidos duas vezes, e nao planos separados. Logo a
        // ordem fisica ja e U V U V -> NAO trocar os pares.
        //
        // Antes: 2 x ByteBuffer.get(indice) por pixel de chroma
        // (~1 milhao de chamadas por frame em 1080p) -> o gargalo
        // de ~12 Mpix/s. Agora: copia a linha inteira de uma vez.
        if (
                uPixelStride == 2 &&
                vPixelStride == 2 &&
                uRowStride == vRowStride
        ) {

            // A ordem fisica do buffer intercalado ja e a que o NV12
            // espera (U0 V0 U1 V1 ...): copiar a linha como esta.
            //
            // A leitura ABSOLUTA no laco antigo (get(indice)) nao
            // mexia na position nem estourava o limit do buffer. O
            // BufferUnderflow de antes vinha de
            // position()+get(byte[],off,len) com len=width, maior que
            // o buffer do plano intercalado.
            //
            // A troca de pares U<->V (assumindo NV21) foi REMOVIDA: era
            // ela que invertia U e V e deixava a imagem azul.
            int uvRowStride =
                    uRowStride;

            // A ROM entrega o plano de chroma SEM padding extra:
            // a ultima linha termina exatamente no fim do buffer
            // (ex.: 1080p -> 540*1920 = 1036800 bytes mas o buffer
            // tem 1036799). Ler "origem + i" com i ate width-1
            // estoura em 1 byte na ultima linha. Por isso todo
            // indice e limitado ao capacity.
            int limite =
                    uBuffer.capacity();

            // COPIA EM BLOCO: o laco antigo fazia 1 ByteBuffer.get(int)
            // por byte de chroma (~1 milhao de leituras aleatorias por
            // frame em 1080p), o que custava ~150 ms/frame no Helio G37
            // e travava o stream em ~6 FPS. Aqui cada linha de chroma
            // inteira e lida de uma vez (get(byte[],off,len)) num
            // buffer reaproveitado. De ~1M chamadas para 540 copias.
            if (
                    linhasUv == null ||
                    linhasUv.length < width
            ) {
                linhasUv =
                        new byte[width];
            }

            for (
                    int row = 0;
                    row < height / 2;
                    row++
            ) {

                int origem =
                        uBase +
                        row * uvRowStride;

                int qtd =
                        Math.min(
                                width,
                                limite - origem
                        );

                if (qtd < 0) {
                    qtd = 0;
                }

                // Zera o resto se a linha estourar o buffer (ultima
                // linha do NV21 sem padding).
                if (qtd < width) {
                    java.util.Arrays.fill(
                            linhasUv,
                            qtd,
                            width,
                            (byte) 0
                    );
                }

                if (qtd > 0) {
                    uBuffer.position(origem);
                    uBuffer.get(
                            linhasUv,
                            0,
                            qtd
                    );
                }

                // U0 V0 U1 V1 ... == ordem que o NV12 espera.
                System.arraycopy(
                        linhasUv,
                        0,
                        nv12,
                        pos,
                        width
                );

                pos += width;
            }

        } else {

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
        }

        // ----------------------------
        // PADDING UV (apenas linhas extras)
        // ----------------------------

        int uvEncHeight = encHeight / 2;
        int uvAltura = height / 2;

        if (uvEncHeight > uvAltura) {

            int inicioUltimaLinhaUV =
                    encHeight * width +
                    (uvAltura - 1) * width;

            for (
                    int row = uvAltura;
                    row < uvEncHeight;
                    row++
            ) {

                int destino =
                        encHeight * width +
                        row * width;

                System.arraycopy(
                        nv12,
                        inicioUltimaLinhaUV,
                        nv12,
                        destino,
                        width
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
        // RESOLUCAO (ADAPTATIVA)
        // ----------------------------

        // O HAL do moto g22 anuncia sizes que ele NAO entrega de
        // fato (ex.: pedimos 854x480 e o sensor manda 960x540). A
        // versao antiga descartava todo frame cujo tamanho nao
        // batesse com CAMERA_WIDTH/HEIGHT -> ~24 de 30 frames/s
        // caíam no lixo e o stream travava em 6 FPS, mesmo com a
        // camera entregando 30 FPS de verdade.
        //
        // Agora: aceita o que a camera mandar. Na primeira vez que
        // vemos um tamanho, aprende e recria o encoder nele. So
        // descarta se a resolucao MUDAR depois de ja termos travado
        // no tamanho aprendido (jitter do sensor), e mesmo assim
        // reaprende em vez de largar o frame pra sempre.
        int larguraFrame = image.getWidth();
        int alturaFrame = image.getHeight();

        if (
                larguraFrame != resolucaoRealLargura ||
                alturaFrame != resolucaoRealAltura ||
                h264Encoder == null
        ) {

            long agora = android.os.SystemClock.elapsedRealtime();

            if (agora - ultimoAvisoResolucao > 2000) {

                ultimoAvisoResolucao = agora;

                Log.w(
                        "LUCAO_H264",
                        "RESOLUCAO APRENDIDA: camera=" +
                                larguraFrame + "x" + alturaFrame +
                                " (encoder estava " +
                                VIDEO_WIDTH + "x" + VIDEO_HEIGHT + ")"
                );
            }

            // Aprende e recria o encoder nesta resolucao. As duas
            // dimensoes viram multiplo de 16 (exigencia do AVC).
            resolucaoRealLargura = larguraFrame;
            resolucaoRealAltura = alturaFrame;
            CAMERA_WIDTH = larguraFrame;
            CAMERA_HEIGHT = alturaFrame;
            VIDEO_WIDTH = arredondarPara16(larguraFrame);
            VIDEO_HEIGHT = arredondarPara16(alturaFrame);

            recriarEncoderH264();

            // Este frame em particular veio antes do encoder novo
            // estar pronto; descarta so ele. O proximo ja entra.
            return;
        }

        if (h264Encoder == null) {

            return;
        }

        try {

            // ====================================================
            // ENTRADA
            // CameraX -> NV12 -> MediaCodec
            // ====================================================

            int inputIndex =
                    codec.dequeueInputBuffer(
                            200_000L
                    );

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

                                enviarFrameVideo(h264);

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
        prepararEncoderH264(true);
    }

    // Recria o encoder DESCARTANDO o atual e usando o
    // VIDEO_WIDTH/VIDEO_HEIGHT ja definidos (o frame real da camera
    // os atualizou). Chamado pelo guard adaptativo de resolucao em
    // processarFrameH264() quando o tamanho entregue pelo sensor
    // difere do que o HAL anunciou.
    private void recriarEncoderH264() {

        // NAO marcar este metodo como synchronized: quem serializa o
        // acesso ao codec e prepararEncoderH264(), que e synchronized.
        // Aqui apenas liberamos o codec antigo e delegamos.
        MediaCodec antigo = h264Encoder;

        h264Encoder = null;

        if (antigo != null) {

            try {
                antigo.stop();
            } catch (Exception ignored) {
            }

            try {
                antigo.release();
            } catch (Exception ignored) {
            }
        }

        // prepararEncoderH264() (synchronized) reutiliza os
        // VIDEO_WIDTH/VIDEO_HEIGHT ja definidos.
        prepararEncoderH264(false);
    }

    // descobrirResolucao=false preserva o VIDEO_WIDTH/VIDEO_HEIGHT
    // ja definidos (usado quando o tamanho veio do frame real da
    // camera, em processarFrameH264()).
    private synchronized void prepararEncoderH264(
            boolean descobrirResolucao
    ) {

        if (h264Encoder != null) {

            Log.i(
                    "LUCAO_H264",
                    "ENCODER JA INICIADO"
            );

            return;
        }

        if (descobrirResolucao) {

        // TUDO NO TALO: descobre a maior resolucao da lente
        // ANTES de criar o encoder, para casar dimensoes.
        CameraSelector seletorResolucao =
                "front".equals(lenteAtual)
                        ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA;

        resolverMaiorResolucao(seletorResolucao);

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
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo
                            .EncoderCapabilities
                            .BITRATE_MODE_CBR
            );

            // NAO setar KEY_LATENCY aqui.
            //
            // Com KEY_LATENCY=1 o encoder MediaTek
            // (c2.mtk.avc.encoder) segurava a fila de entrada e
            // travava o fluxo. Deixando o codec escolher o
            // paralelismo natural, o dequeueInputBuffer fica pronto
            // na hora.

            // 0 = todo frame e keyframe (I-frame). O PC so fecha
            // fragmento fMP4 em keyframe (-movflags frag_keyframe),
            // entao com GOP de 1s a imagem no PC podia esperar ate
            // ~1s para aparecer. O harness de latencia
            // (tmp/latencia-harness.js) mostrou que o piso do bridge
            // e ~1 frame (33ms); all-intra elimina esse pior caso.
            format.setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    0
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
        pararEscritorVideo();

        pararDescoberta();

        super.onDestroy();
    }

    // ============================================================
    // ESCRITOR DE VIDEO (FILA LIMITADA, DESCARTA O MAIS ANTIGO)
    // ============================================================

    private void iniciarEscritorVideo() {

        videoWriteQueue =
                new java.util.concurrent.ArrayBlockingQueue<byte[]>(
                        VIDEO_WRITE_MAX_RENDEZ
                );

        videoWriterAtivo = true;

        videoWriterThread = new Thread(() -> {

            while (videoWriterAtivo) {

                final byte[] frame;

                try {
                    frame = videoWriteQueue.take();
                } catch (InterruptedException e) {
                    break;
                }

                OutputStream output = videoOutput;

                if (output == null || frame == null) {
                    continue;
                }

                try {

                    output.write(frame);

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

                    // Sincroniza de novo quando reconectar.
                    solicitarKeyframeH264 = true;
                }
            }
        }, "lucao-video-writer");

        videoWriterThread.setDaemon(true);
        videoWriterThread.start();
    }

    private void pararEscritorVideo() {

        videoWriterAtivo = false;

        if (videoWriteQueue != null) {
            videoWriteQueue.clear();
        }

        if (videoWriterThread != null) {
            videoWriterThread.interrupt();
            videoWriterThread = null;
        }
    }

    // Coloca o frame na fila sem bloquear. Se a fila estiver cheia
    // (rede lenta), remove o frame mais antigo para o fluxo voltar
    // ao vivo e marca que precisamos de um novo keyframe, senao o
    // decodificador do PC fica sem referencia.
    private void enviarFrameVideo(byte[] h264) {

        java.util.concurrent.ArrayBlockingQueue<byte[]> fila =
                videoWriteQueue;

        if (!videoWriterAtivo || fila == null || h264 == null) {
            return;
        }

        if (!fila.offer(h264)) {

            byte[] descartado = fila.poll();

            if (descartado != null) {
                fila.offer(h264);

                solicitarKeyframeH264 = true;
            }
        }
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

            } else if (ACTION_SET_LENTE.equals(action)) {

                String lente =
                        intent.getStringExtra("lente");

                trocarLente(lente);

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

            } else if ("lente".equals(tipo)) {

                trocarLente(comando.optString("valor", ""));

            } else if ("flash".equals(tipo)) {

                aplicarFlash(comando.optBoolean("ligado", false));

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

    private void aplicarFlash(boolean ligado) {

        ContextCompat.getMainExecutor(this).execute(() -> {

            if (cameraAtual == null) {

                Log.w(
                        "LUCAO_CONTROL",
                        "FLASH ignorado - cameraAtual ainda e nula"
                );
                return;
            }

            try {

                cameraAtual
                        .getCameraControl()
                        .enableTorch(ligado);

                flashLigado = ligado;

                Log.i(
                        "LUCAO_CONTROL",
                        "FLASH " + (ligado ? "ON" : "OFF")
                );

                broadcastStatus();

            } catch (Exception e) {

                Log.e(
                        "LUCAO_CONTROL",
                        "ERRO AO APLICAR FLASH: " + e.getMessage()
                );
            }
        });
    }

    private void trocarLente(String lente) {

        if (lente == null) {
            return;
        }

        lente = lente.trim().toLowerCase();

        if (!"back".equals(lente) && !"front".equals(lente)) {
            return;
        }

        if (lente.equals(lenteAtual)) {
            return;
        }

        // So troca para uma lente que o aparelho realmente expoe.
        if (!lentesDisponiveis.isEmpty() &&
                !lentesDisponiveis.contains(lente)) {

            Log.w(
                    "LUCAO_CONTROL",
                    "LENTE INDISPONIVEL: " + lente
            );

            return;
        }

        lenteAtual = lente;

        final String lenteSelecionada = lente;

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_LENTE, lente)
                .apply();

        Log.i(
                "LUCAO_CONTROL",
                "TROCANDO PARA LENTE: " + lente
        );

        // Reinicia a camera na Thread principal: unbindAll + rebind
        // com o novo CameraSelector. Cada lente pode expor uma
        // resolucao maxima diferente; se mudar, o encoder H.264 e
        // recriado para casar as dimensoes.
        ContextCompat
                .getMainExecutor(this)
                .execute(() -> {

                    int larguraAntes = VIDEO_WIDTH;
                    int alturaAntes = VIDEO_HEIGHT;

                    CameraSelector seletor =
                            "front".equals(lenteSelecionada)
                                    ? CameraSelector.DEFAULT_FRONT_CAMERA
                                    : CameraSelector.DEFAULT_BACK_CAMERA;

                    resolverMaiorResolucao(seletor);

                    if (VIDEO_WIDTH != larguraAntes ||
                            VIDEO_HEIGHT != alturaAntes) {

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

                        prepararEncoderH264();
                    }

                    if (cameraProvider != null) {
                        cameraProvider.unbindAll();
                    }

                    // A lente nova gera um stream novo; o decodificador
                    // do PC precisa de um I-frame para reancorar. Sem
                    // isso a imagem ficava preta ate o proximo keyframe
                    // periodico.
                    solicitarKeyframeH264 = true;

                    iniciarCamera();
                });
    }

}
