package com.lucao.link;

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

    private static final String VIDEO_HOST = "127.0.0.1";
    private static final int VIDEO_PORT = 5051;

    // ============================================================
    // H264 / TCP
    // ============================================================

    private volatile MediaCodec h264Encoder;

    private volatile Socket videoSocket;
    private volatile OutputStream videoOutput;

    // ============================================================
    // EXECUTORES
    // ============================================================

    private ExecutorService cameraExecutor;
    private ExecutorService networkExecutor;
    private ProcessCameraProvider cameraProvider;
    private Camera cameraAtual;

    // ============================================================
    // ESTADO
    // ============================================================

    private volatile boolean pcConectado = false;
    private volatile boolean cameraAtiva = true;
    private volatile boolean solicitarKeyframeH264 = false;

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

listarFpsCamera();

cameraExecutor =
        Executors.newSingleThreadExecutor();

    networkExecutor =
            Executors.newFixedThreadPool(2);

    // Lifecycle usado pelo CameraX
    lifecycleRegistry =
            new LifecycleRegistry(this);

    lifecycleRegistry.setCurrentState(
            Lifecycle.State.CREATED
    );

    // Foreground Service precisa da notificação
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

    // Agora o Service está ativo para o CameraX
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

            Log.e(
                    "LUCAO_SERVICE",
                    "TCP 5051 INDISPONIVEL"
            );

            return;
        }

        pcConectado = true;

        prepararEncoderH264();

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
                    "Mantém a webcam LUCAO LINK ativa"
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
}

    private synchronized void conectarVideoTcp() {

        if (
                videoSocket != null &&
                videoSocket.isConnected() &&
                !videoSocket.isClosed()
        ) {
            return;
        }

        try {

            Socket socket =
                    new Socket(
                            VIDEO_HOST,
                            VIDEO_PORT
                    );

            socket.setTcpNoDelay(true);

            OutputStream output =
                    socket.getOutputStream();

            videoSocket = socket;
            videoOutput = output;

            Log.i(
                    "LUCAO_TCP",
                    "VIDEO TCP CONECTADO: " +
                            VIDEO_HOST +
                            ":" +
                            VIDEO_PORT
            );

        } catch (Exception e) {

            Log.e(
                    "LUCAO_TCP",
                    "ERRO AO CONECTAR VIDEO TCP",
                    e
            );

            videoSocket = null;
            videoOutput = null;
        }
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

            cameraProvider.unbindAll();

            cameraAtual =
                    cameraProvider.bindToLifecycle(
                            this,
                            cameraSelector,
                            imageAnalysis
                    );

            Log.i(
                    "LUCAO_CAMERA",
                    "CAMERA DO SERVICE INICIADA: " +
                            VIDEO_WIDTH + "x" +
                            VIDEO_HEIGHT +
                            " @ " +
                            VIDEO_FPS +
                            " FPS"
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

    Log.i(
            "LUCAO_FRAME",
            "FRAME RECEBIDO: " +
                    image.getWidth() + "x" +
                    image.getHeight()
    );

    try {

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

            // Caminho rápido.
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

                            ExecutorService executor =
        networkExecutor;

if (executor != null) {

    executor.execute(() -> {

        OutputStream output =
                videoOutput;

        if (output == null) {
            return;
        }

        try {

            output.write(h264);

        } catch (IOException e) {

            Log.e(
                    "LUCAO_TCP",
                    "CONEXAO H264 PERDIDA: " +
                            e.getMessage()
            );

            desconectarVideoTcp();

            try {

                Thread.sleep(500);

            } catch (InterruptedException e2) {

                Thread.currentThread()
                        .interrupt();

                return;
            }

            conectarVideoTcp();

            if (videoOutput != null) {

                pcConectado = true;

                solicitarKeyframeH264 = true;

                Log.i(
        "LUCAO_TCP",
        "VIDEO TCP RECONECTADO"
);
            }
        }
    });
}

                            if (
                                    (
                                            info.flags &
                                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                                    ) == 0
                            ) {

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

                            output.write(
                                    dadosSps
                            );

                            Log.i(
                                    "LUCAO_TCP",
                                    "SPS ENVIADO: " +
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

                            output.write(
                                    dadosPps
                            );

                            Log.i(
                                    "LUCAO_TCP",
                                    "PPS ENVIADO: " +
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

        super.onDestroy();
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
}