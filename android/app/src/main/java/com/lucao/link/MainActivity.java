package com.lucao.link;

import java.net.Socket;
import java.io.OutputStream;
import android.util.Range;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import android.media.MediaCodec;
import android.view.Surface;


public class MainActivity extends AppCompatActivity {

	private boolean primeiroFrameH264Enviado = false;

	private volatile MediaCodec h264Encoder;
    private long h264JanelaInicioMs = 0;
private int h264FramesEntrada = 0;
private int h264FramesSaida = 0;
private long h264BytesSaida = 0;
    private Surface h264InputSurface;
	private VideoCapture<Recorder> videoCapture;

    private static final int VIDEO_WIDTH = 640;
private static final int VIDEO_HEIGHT = 480;
private static final int VIDEO_FPS = 30;
private static final int VIDEO_BITRATE = 2_000_000;

private static final String VIDEO_HOST = "192.168.2.121";
private static final int VIDEO_PORT = 5051;

private volatile Socket videoSocket;
private volatile OutputStream videoOutput;

    private TextView statusText;
private TextView connectionBadge;

private Button connectButton;

private PreviewView cameraPreview;

private android.widget.Switch webcamSwitch;
private android.widget.Switch microphoneSwitch;

    private ExecutorService cameraExecutor;
    private ExecutorService networkExecutor;

    private volatile boolean pcConectado = false;
    private volatile boolean cameraAtiva = true;
    private volatile boolean solicitarKeyframeH264 = false;
    private long ultimoEnvio = 0;

    private static final String SERVER =
            "http://192.168.2.121:5050/";

    private static final String FRAME_URL =
            "http://192.168.2.121:5050/frame";

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    permitido -> {
                        if (permitido) {
                            iniciarCamera();
                        } else {
                            statusText.setText("CAMERA NAO AUTORIZADA");
                            statusText.setTextColor(Color.RED);
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
cameraExecutor =
        java.util.concurrent.Executors
                .newSingleThreadExecutor();
                networkExecutor =
        new java.util.concurrent.ThreadPoolExecutor(
                1,
                1,
                0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(2),
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
        );
        java.util.concurrent.Executors
                .newSingleThreadExecutor();
        statusText = findViewById(R.id.statusText);
connectionBadge = findViewById(R.id.connectionBadge);

connectButton = findViewById(R.id.connectButton);

cameraPreview = findViewById(R.id.cameraPreview);

webcamSwitch = findViewById(R.id.webcamSwitch);
microphoneSwitch = findViewById(R.id.microphoneSwitch);
cameraAtiva = webcamSwitch.isChecked();

webcamSwitch.setOnCheckedChangeListener(
        (buttonView, isChecked) -> {

            cameraAtiva = isChecked;

            if (isChecked) {
    solicitarKeyframeH264 = true;
}

            Log.i(
                    "LUCAO_UI",
                    "WEBCAM " + (isChecked ? "ON" : "OFF")
            );
        }
);

connectButton.setOnClickListener(
        v -> testarConexao()
);

        verificarCamera();
listarEncodersH264();
listarFormatosH264();
    }

    private void verificarCamera() {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED) {

            iniciarCamera();

        } else {

            cameraPermissionLauncher.launch(
                    Manifest.permission.CAMERA
            );
        }
    }

    private void iniciarCamera() {

    ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
            ProcessCameraProvider.getInstance(this);

    cameraProviderFuture.addListener(() -> {

        try {

            ProcessCameraProvider cameraProvider =
                    cameraProviderFuture.get();

            // Preview da câmera na tela do celular
            Preview preview =
        new Preview.Builder()
                .setTargetFrameRate(
                        new Range<>(
                                VIDEO_FPS,
                                VIDEO_FPS
                        )
                )
                .build();

            preview.setSurfaceProvider(
                    cameraPreview.getSurfaceProvider()
            );

            // Preferência de resolução: 640x480 (4:3)
            ResolutionSelector resolutionSelector =
                    new ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                            )
                            .setResolutionStrategy(
                                    new ResolutionStrategy(
                                            new android.util.Size(
                                                    VIDEO_WIDTH,
                                                    VIDEO_HEIGHT
                                            ),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                    )
                            )
                            .build();

            // Frames que serão processados pelo LUCAO LINK
            ImageAnalysis imageAnalysis =
                    new ImageAnalysis.Builder()
                            .setResolutionSelector(
                                    resolutionSelector
                            )
                            .setBackpressureStrategy(
                                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                            )
                            .build();

            imageAnalysis.setAnalyzer(
                    cameraExecutor,
                    this::analisarFrame
            );

            // Câmera traseira
            CameraSelector cameraSelector =
                    CameraSelector.DEFAULT_BACK_CAMERA;

            // Remove configurações anteriores
            cameraProvider.unbindAll();

            // Preview + análise.
            // NÃO usamos VideoCapture aqui.
            cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
            );

            Log.i(
                    "LUCAO_H264",
                    "CAMERA CONFIGURADA: alvo=" +
                            VIDEO_WIDTH + "x" +
                            VIDEO_HEIGHT
            );

        } catch (Exception e) {

            Log.e(
                    "LUCAO_H264",
                    "ERRO AO INICIAR CAMERA",
                    e
            );

            statusText.setText("ERRO NA CAMERA");
            statusText.setTextColor(Color.RED);
        }

    }, ContextCompat.getMainExecutor(this));
}

    private void analisarFrame(ImageProxy image) {

    try {

        if (!pcConectado) {
    return;
}

if (!cameraAtiva) {
    return;
}

// Caminho principal do LUCAO LINK:
// CameraX -> NV12 -> MediaCodec H.264
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

    private byte[] converterParaNV12(ImageProxy image) {

    int width = image.getWidth();
    int height = image.getHeight();

    ImageProxy.PlaneProxy[] planes = image.getPlanes();

    byte[] nv12 = new byte[width * height * 3 / 2];

    int pos = 0;

    ByteBuffer yBuffer = planes[0].getBuffer().duplicate();

    int yBase = yBuffer.position();
    int yRowStride = planes[0].getRowStride();
    int yPixelStride = planes[0].getPixelStride();

    if (yPixelStride == 1) {

    // Caminho rápido:
    // copia uma linha Y inteira de uma vez.
    for (int row = 0; row < height; row++) {

        int rowStart =
                yBase + row * yRowStride;

        yBuffer.position(rowStart);

        yBuffer.get(
                nv12,
                pos,
                width
        );

        pos += width;
    }

} else {

    // Fallback para dispositivos com layout diferente.
    for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {

            nv12[pos++] = yBuffer.get(
                    yBase +
                    row * yRowStride +
                    col * yPixelStride
            );
        }
    }
}

    ByteBuffer uBuffer = planes[1].getBuffer().duplicate();
    ByteBuffer vBuffer = planes[2].getBuffer().duplicate();

    int uBase = uBuffer.position();
    int vBase = vBuffer.position();

    int uRowStride = planes[1].getRowStride();
    int vRowStride = planes[2].getRowStride();

    int uPixelStride = planes[1].getPixelStride();
    int vPixelStride = planes[2].getPixelStride();

    for (int row = 0; row < height / 2; row++) {
        for (int col = 0; col < width / 2; col++) {

            nv12[pos++] = uBuffer.get(
                    uBase +
                    row * uRowStride +
                    col * uPixelStride
            );

            nv12[pos++] = vBuffer.get(
                    vBase +
                    row * vRowStride +
                    col * vPixelStride
            );
        }
    }

    return nv12;
}

private void processarFrameH264(ImageProxy image) {


    MediaCodec codec = h264Encoder;

    if (codec == null) {
        return;
    }

    // Se a webcam acabou de ser reativada,
    // pede um novo keyframe ao encoder H.264.
    if (solicitarKeyframeH264) {

        try {

            Bundle parametros = new Bundle();

            parametros.putInt(
                    MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,
                    0
            );

            codec.setParameters(parametros);

            solicitarKeyframeH264 = false;

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

    if (
            image.getWidth() != VIDEO_WIDTH ||
            image.getHeight() != VIDEO_HEIGHT
    ) {

        Log.w(
                "LUCAO_H264",
                "FRAME IGNORADO: camera=" +
                        image.getWidth() + "x" +
                        image.getHeight() +
                        " encoder=" +
                        VIDEO_WIDTH + "x" +
                        VIDEO_HEIGHT
        );

        return;
    }

    try {

        // ========================================
        // ENTRADA: CameraX -> NV12 -> MediaCodec
        // ========================================

        int inputIndex =
        codec.dequeueInputBuffer(0);

if (inputIndex >= 0) {

    byte[] nv12 =
            converterParaNV12(image);

    ByteBuffer inputBuffer =
            codec.getInputBuffer(inputIndex);

    if (inputBuffer != null) {

        inputBuffer.clear();

        if (inputBuffer.remaining() >= nv12.length) {

            inputBuffer.put(nv12);

            long presentationTimeUs =
                    image.getImageInfo()
                            .getTimestamp() / 1000L;

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
                    image.getImageInfo()
                            .getTimestamp() / 1000L,
                    0
            );
        }
    }
}

        // ========================================
        // SAIDA: MediaCodec -> H.264
        // ========================================

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
            codec.getOutputBuffer(outputIndex);

    if (outputBuffer != null) {

        outputBuffer.position(info.offset);
        outputBuffer.limit(
                info.offset + info.size
        );

        byte[] h264 =
                new byte[info.size];

        outputBuffer.get(h264);

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
                    "ERRO AO ENVIAR H264",
                    e
            );
        }
    });
}

        // Codec config contém SPS/PPS.
        // Enviamos pelo TCP, mas não contamos
        // como frame de vídeo.
        if (
                (info.flags &
                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
        ) {

            h264FramesSaida++;
            h264BytesSaida += info.size;
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
                outputFormat.getByteBuffer("csd-0");

        ByteBuffer pps =
                outputFormat.getByteBuffer("csd-1");

        if (sps != null) {

            ByteBuffer copiaSps =
                    sps.duplicate();

            copiaSps.position(0);

            byte[] dadosSps =
                    new byte[copiaSps.remaining()];

            copiaSps.get(dadosSps);

            output.write(dadosSps);

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
                    new byte[copiaPps.remaining()];

            copiaPps.get(dadosPps);

            output.write(dadosPps);

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

        // ========================================
        // ESTATISTICA A CADA ~1 SEGUNDO
        // ========================================

        long agora =
                System.currentTimeMillis();

        if (h264JanelaInicioMs == 0) {
            h264JanelaInicioMs = agora;
        }

        long intervalo =
                agora - h264JanelaInicioMs;

        if (intervalo >= 1000) {

            double fpsEntrada =
                    h264FramesEntrada *
                            1000.0 / intervalo;

            double fpsSaida =
                    h264FramesSaida *
                            1000.0 / intervalo;

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
            h264JanelaInicioMs = agora;
        }

    } catch (Exception e) {

        Log.e(
                "LUCAO_H264",
                "ERRO NO H264 CONTINUO",
                e
        );
    }
}

    private void enviarFrame(byte[] jpeg) {

        HttpURLConnection connection = null;

        try {

            URL url =
                    new URL(FRAME_URL);

            connection =
                    (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setDoOutput(true);

            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);

            connection.setRequestProperty(
                    "Content-Type",
                    "image/jpeg"
            );

            connection.setFixedLengthStreamingMode(
                    jpeg.length
            );

            try (
                    OutputStream output =
                            connection.getOutputStream()
            ) {

                output.write(jpeg);
            }

            connection.getResponseCode();

        } catch (IOException e) {

            e.printStackTrace();

        } finally {

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private synchronized void prepararEncoderH264() {

    if (h264Encoder != null) {
        Log.i("LUCAO_H264", "ENCODER JA INICIADO");
        return;
    }

    MediaCodec codec = null;

    try {

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                VIDEO_WIDTH,
                VIDEO_HEIGHT
        );

        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
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
                MediaFormat.KEY_I_FRAME_INTERVAL,
                1
        );

        codec = MediaCodec.createByCodecName(
                "OMX.qcom.video.encoder.avc"
        );

        codec.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        codec.start();

        // Só fica disponível para a câmera DEPOIS do start().
        h264Encoder = codec;

        Log.i(
                "LUCAO_H264",
                "ENCODER INICIADO: " +
                        VIDEO_WIDTH + "x" +
                        VIDEO_HEIGHT + " @" +
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
            } catch (Exception ignored) {
            }
        }

        h264Encoder = null;
    }
}

    private void listarEncodersH264() {

    MediaCodecList codecList =
            new MediaCodecList(MediaCodecList.ALL_CODECS);

    for (MediaCodecInfo codecInfo :
            codecList.getCodecInfos()) {

        if (!codecInfo.isEncoder()) {
            continue;
        }

        for (String tipo :
                codecInfo.getSupportedTypes()) {

            if (tipo.equalsIgnoreCase(
                    MediaFormat.MIMETYPE_VIDEO_AVC
            )) {

                Log.i(
                        "LUCAO_CODEC",
                        "H264 ENCODER: " +
                        codecInfo.getName() +
                        " | hardware=" +
                        codecInfo.isHardwareAccelerated()
                );
            }
        }
    }
}

private void listarFormatosH264() {

    try {

        MediaCodecInfo codecInfo = null;

        MediaCodecList codecList =
                new MediaCodecList(
                        MediaCodecList.ALL_CODECS
                );

        for (MediaCodecInfo info :
                codecList.getCodecInfos()) {

            if (
                    info.isEncoder() &&
                    info.getName().equals(
                            "OMX.qcom.video.encoder.avc"
                    )
            ) {
                codecInfo = info;
                break;
            }
        }

        if (codecInfo == null) {

            Log.e(
                    "LUCAO_FORMAT",
                    "ENCODER QUALCOMM NAO ENCONTRADO"
            );

            return;
        }

        MediaCodecInfo.CodecCapabilities caps =
                codecInfo.getCapabilitiesForType(
                        MediaFormat.MIMETYPE_VIDEO_AVC
                );

        for (int formato : caps.colorFormats) {

            Log.i(
                    "LUCAO_FORMAT",
                    "COLOR FORMAT: " + formato
            );
        }

    } catch (Exception e) {

        Log.e(
                "LUCAO_FORMAT",
                "ERRO AO LISTAR FORMATOS",
                e
        );
    }
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

        Socket socket = new Socket(
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
                        VIDEO_HOST + ":" +
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

    private void testarConexao() {

        Log.i(
        "LUCAO_NET",
        "BOTAO CONECTAR - servidor=" + SERVER
);

        statusText.setText("CONECTANDO...");
        statusText.setTextColor(Color.YELLOW);
        connectionBadge.setText("● CONECTANDO");
connectionBadge.setTextColor(Color.rgb(167, 139, 250));

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
        "LUCAO_NET",
        "HTTP RESPONSE=" + response
);


                if (
                        response >= 200 &&
                        response < 300
                ) {

                    pcConectado = true;
					conectarVideoTcp();
					prepararEncoderH264();

                    runOnUiThread(() -> {

                        statusText.setText(
                                "PC CONECTADO"
                        );

                        statusText.setTextColor(
                                Color.rgb(0, 255, 136)
                        );
                        connectionBadge.setText("● CONECTADO");
connectionBadge.setTextColor(
        Color.rgb(57, 255, 136)
);

                        connectButton.setText(
                                "CONECTADO"
                        );

                        connectButton.setEnabled(true);
                    });

                } else {

                    pcConectado = false;

                    mostrarErro(
                            "ERRO HTTP " + response
                    );
                }

            } catch (IOException e) {

    pcConectado = false;

    Log.e(
            "LUCAO_NET",
            "ERRO NA CONEXAO HTTP",
            e
    );

    mostrarErro(
            "PC NAO ENCONTRADO"
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
            statusText.setTextColor(Color.RED);
            connectionBadge.setText("● OFFLINE");
connectionBadge.setTextColor(Color.RED);

            connectButton.setText(
                    "TENTAR NOVAMENTE"
            );

            connectButton.setEnabled(true);
        });
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}