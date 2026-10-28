package com.labelscan.pro;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import android.util.Size;

/** CameraX preview with ML Kit and ZXing analyzing the same camera frames. */
public class DualScannerActivity extends ComponentActivity {
    private PreviewView previewView;
    private TextView statusView;
    private TextView resultView;
    private ExecutorService cameraExecutor;
    private BarcodeScanner mlKitScanner;
    private MultiFormatReader zxingReader;
    private ProcessCameraProvider cameraProvider;
    private Preview previewUseCase;
    private ImageAnalysis analysisUseCase;
    private Camera camera;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private boolean torchEnabled = false;
    private float zoomRatio = 1.0f;
    private final AtomicBoolean frameBusy = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();
    private volatile boolean closing = false;
    private boolean closeEventSent = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DualScannerPlugin.setActiveActivity(this);
        buildUi();
        cameraExecutor = Executors.newSingleThreadExecutor();
        BarcodeScannerOptions mlOptions = new BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build();
        mlKitScanner = BarcodeScanning.getClient(mlOptions);
        zxingReader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(com.google.zxing.BarcodeFormat.values()));
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        zxingReader.setHints(hints);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusView.setText("카메라 권한이 없습니다. 앱 설정에서 허용한 뒤 다시 시도하세요.");
            return;
        }
        startCamera();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(14), dp(12), dp(14));
        top.setBackgroundColor(0xCC07111C);
        TextView title = new TextView(this);
        title.setText("LabelScan PRO  ·  ML Kit + ZXing");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button switchCamera = new Button(this);
        switchCamera.setText("전/후면");
        switchCamera.setOnClickListener(v -> {
            lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            bindCameraUseCases();
        });
        top.addView(switchCamera);
        Button torch = new Button(this);
        torch.setText("손전등");
        torch.setOnClickListener(v -> {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                torchEnabled = !torchEnabled;
                camera.getCameraControl().enableTorch(torchEnabled);
                torch.setText(torchEnabled ? "손전등 켬" : "손전등");
            } else {
                statusView.setText("현재 카메라에서 손전등을 지원하지 않습니다.");
            }
        });
        top.addView(torch);
        Button close = new Button(this);
        close.setText("닫기");
        close.setOnClickListener(v -> finish());
        top.addView(close);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(top, topParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(14), dp(16), dp(22));
        bottom.setBackgroundColor(0xD907111C);
        statusView = new TextView(this);
        statusView.setText("ML Kit + ZXing 동시 분석 중 · 모든 지원 형식");
        statusView.setTextColor(0xFFB9C8D8);
        statusView.setTextSize(12);
        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        Button zoomOut = new Button(this);
        zoomOut.setText("− 줌");
        zoomOut.setOnClickListener(v -> changeZoom(-0.5f));
        Button zoomIn = new Button(this);
        zoomIn.setText("+ 줌");
        zoomIn.setOnClickListener(v -> changeZoom(0.5f));
        zoomRow.addView(zoomOut, new LinearLayout.LayoutParams(0, -2, 1));
        zoomRow.addView(zoomIn, new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(zoomRow);
        bottom.addView(statusView);
        resultView = new TextView(this);
        resultView.setText("바코드를 화면 중앙에 비춰 주세요.");
        resultView.setTextColor(Color.WHITE);
        resultView.setTextSize(18);
        resultView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        resultView.setPadding(0, dp(8), 0, 0);
        bottom.addView(resultView);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bottom, bottomParams);
        setContentView(root);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                cameraProvider = provider;
                previewUseCase = new Preview.Builder().build();
                previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());
                analysisUseCase = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analysisUseCase.setAnalyzer(cameraExecutor, this::analyzeFrame);
                bindCameraUseCases();
            } catch (Exception ex) {
                runOnUiThread(() -> statusView.setText("카메라 시작 실패: " + ex.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }


    private void bindCameraUseCases() {
        if (cameraProvider == null || previewUseCase == null || analysisUseCase == null || closing) return;
        try {
            cameraProvider.unbindAll();
            CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
            camera = cameraProvider.bindToLifecycle(this, selector, previewUseCase, analysisUseCase);
            torchEnabled = false;
            zoomRatio = 1.0f;
            camera.getCameraControl().setZoomRatio(zoomRatio);
        } catch (Exception ex) {
            runOnUiThread(() -> statusView.setText("선택한 카메라를 사용할 수 없습니다: " + ex.getMessage()));
            lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        }
    }

    private void changeZoom(float delta) {
        if (camera == null) return;
        androidx.camera.core.ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        if (state == null) return;
        zoomRatio = Math.max(state.getMinZoomRatio(), Math.min(state.getMaxZoomRatio(), zoomRatio + delta));
        camera.getCameraControl().setZoomRatio(zoomRatio);
        statusView.setText("ML Kit + ZXing 동시 분석 중 · 줌 " + String.format(java.util.Locale.US, "%.1fx", zoomRatio));
    }

    private void analyzeFrame(ImageProxy proxy) {
        if (closing || !frameBusy.compareAndSet(false, true)) { proxy.close(); return; }
        android.media.Image mediaImage = proxy.getImage();
        if (mediaImage == null) { proxy.close(); frameBusy.set(false); return; }
        final int rotation = proxy.getImageInfo().getRotationDegrees();
        final byte[] luma;
        final int width = proxy.getWidth();
        final int height = proxy.getHeight();
        try { luma = copyLuma(proxy); }
        catch (Exception ex) { proxy.close(); frameBusy.set(false); return; }

        // ML Kit consumes the camera image asynchronously while ZXing decodes a copied
        // luminance plane on this analyzer thread: both engines inspect the same frame.
        try {
            InputImage input = InputImage.fromMediaImage(mediaImage, rotation);
            mlKitScanner.process(input)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String value = barcode.getRawValue();
                        if (value != null && !value.trim().isEmpty()) emitUnique(value, mlKitFormat(barcode.getFormat()), "ML Kit");
                    }
                })
                .addOnFailureListener(error -> runOnUiThread(() -> statusView.setText("ML Kit 분석 재시도 중 · ZXing 활성")))
                .addOnCompleteListener(task -> {
                    proxy.close();
                    frameBusy.set(false);
                });
        } catch (Exception ex) {
            proxy.close();
            frameBusy.set(false);
        }
        try {
            byte[] rotated = rotateLuma(luma, width, height, rotation);
            int rw = (rotation == 90 || rotation == 270) ? height : width;
            int rh = (rotation == 90 || rotation == 270) ? width : height;
            LuminanceSource source = new PlanarYUVLuminanceSource(rotated, rw, rh, 0, 0, rw, rh, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = zxingReader.decodeWithState(bitmap);
            if (result != null && result.getText() != null) emitUnique(result.getText(), result.getBarcodeFormat().toString(), "ZXing");
        } catch (NotFoundException ignored) {
        } catch (Exception ignored) {
        } finally {
            zxingReader.reset();
        }
    }

    private byte[] copyLuma(ImageProxy proxy) {
        ImageProxy.PlaneProxy plane = proxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int width = proxy.getWidth(), height = proxy.getHeight();
        int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        byte[] out = new byte[width * height];
        for (int y = 0; y < height; y++) {
            int rowStart = y * rowStride;
            for (int x = 0; x < width; x++) out[y * width + x] = buffer.get(rowStart + x * pixelStride);
        }
        return out;
    }

    private byte[] rotateLuma(byte[] src, int width, int height, int rotation) {
        if (rotation == 0) return src;
        int outW = (rotation == 90 || rotation == 270) ? height : width;
        int outH = (rotation == 90 || rotation == 270) ? width : height;
        byte[] out = new byte[src.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int nx, ny;
                if (rotation == 90) { nx = height - 1 - y; ny = x; }
                else if (rotation == 180) { nx = width - 1 - x; ny = height - 1 - y; }
                else if (rotation == 270) { nx = y; ny = width - 1 - x; }
                else { nx = x; ny = y; }
                out[ny * outW + nx] = src[y * width + x];
            }
        }
        return out;
    }

    private String mlKitFormat(int format) {
        switch (format) {
            case Barcode.FORMAT_CODE_128: return "CODE_128";
            case Barcode.FORMAT_CODE_39: return "CODE_39";
            case Barcode.FORMAT_CODE_93: return "CODE_93";
            case Barcode.FORMAT_CODABAR: return "CODABAR";
            case Barcode.FORMAT_EAN_13: return "EAN_13";
            case Barcode.FORMAT_EAN_8: return "EAN_8";
            case Barcode.FORMAT_ITF: return "ITF";
            case Barcode.FORMAT_UPC_A: return "UPC_A";
            case Barcode.FORMAT_UPC_E: return "UPC_E";
            case Barcode.FORMAT_QR_CODE: return "QR_CODE";
            case Barcode.FORMAT_PDF417: return "PDF417";
            case Barcode.FORMAT_AZTEC: return "AZTEC";
            case Barcode.FORMAT_DATA_MATRIX: return "DATA_MATRIX";
            default: return "FORMAT_" + format;
        }
    }

    private void emitUnique(String value, String format, String engine) {
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean shouldEmit = new java.util.concurrent.atomic.AtomicBoolean(false);
        lastSeen.compute(value, (key, prior) -> {
            if (prior == null || now - prior >= 1200) {
                shouldEmit.set(true);
                return now;
            }
            return prior;
        });
        if (!shouldEmit.get()) return;
        if (lastSeen.size() > 300) {
            for (Map.Entry<String, Long> item : lastSeen.entrySet()) if (now - item.getValue() > 10000) lastSeen.remove(item.getKey(), item.getValue());
        }
        runOnUiThread(() -> {
            if (closing) return;
            resultView.setText(value);
            statusView.setText("동시 판독 완료 · " + format + " · " + engine);
        });
        runOnUiThread(() -> DualScannerPlugin.emitBarcode(value, format, engine));
    }

    @Override
    protected void onDestroy() {
        closing = true;
        try { if (mlKitScanner != null) mlKitScanner.close(); } catch (Exception ignored) {}
        try { if (cameraExecutor != null) cameraExecutor.shutdown(); } catch (Exception ignored) {}
        DualScannerPlugin.setActiveActivity(null);
        if (!closeEventSent) {
            closeEventSent = true;
            DualScannerPlugin.emitClosed();
        }
        super.onDestroy();
    }
}
