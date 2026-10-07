package com.rishiraj.punchcapture;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photo-only camera: flash always off, full-quality capture, and the saved JPEG
 * is rotated in its pixels to match how the device was held (no sideways images).
 * Shutter: on-screen button, volume keys, camera key or the Zebra side triggers.
 */
public class CameraActivity extends ComponentActivity {

    public static final String EXTRA_OUT = "out";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_ERROR = "error";
    private static final int REQ_PERM = 7;

    private PreviewView preview;
    private ImageCapture capture;
    private Camera camera;
    private TextView status;
    private View shutter;
    private File out;
    private OrientationEventListener orientation;
    private volatile int rotation = Surface.ROTATION_0;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        String path = getIntent().getStringExtra(EXTRA_OUT);
        if (path == null) { fail("No output file."); return; }
        out = new File(path);
        buildUi(getIntent().getStringExtra(EXTRA_LABEL));

        orientation = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int deg) {
                if (deg == ORIENTATION_UNKNOWN) return;
                int r;
                if (deg >= 45 && deg < 135) r = Surface.ROTATION_270;
                else if (deg >= 135 && deg < 225) r = Surface.ROTATION_180;
                else if (deg >= 225 && deg < 315) r = Surface.ROTATION_90;
                else r = Surface.ROTATION_0;
                if (r != rotation) {
                    rotation = r;
                    if (capture != null) capture.setTargetRotation(r);
                }
            }
        };

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientation != null && orientation.canDetectOrientation()) orientation.enable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (orientation != null) orientation.disable();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else fail("Camera access is off. Allow Camera for Punch Capture in Settings → Apps.");
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi(String label) {
        FrameLayout rootView = new FrameLayout(this);
        rootView.setBackgroundColor(Color.BLACK);

        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        preview.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPoint pt = preview.getMeteringPointFactory().createPoint(e.getX(), e.getY());
                camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(pt).build());
                v.performClick();
            }
            return true;
        });
        rootView.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        TextView top = new TextView(this);
        top.setText(label == null ? "" : label);
        top.setTextColor(Color.WHITE);
        top.setTextSize(16);
        top.setPadding(dp(16), dp(14), dp(16), dp(14));
        top.setBackgroundColor(0x99000000);
        rootView.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(22));
        bar.setBackgroundColor(0x99000000);

        TextView cancel = new TextView(this);
        cancel.setText("Cancel");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(16);
        cancel.setPadding(dp(8), dp(12), dp(8), dp(12));
        cancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        bar.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));

        shutter = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        ring.setStroke(dp(5), 0xFF0A6EBD);
        shutter.setBackground(ring);
        shutter.setContentDescription("Take photo");
        shutter.setOnClickListener(v -> shoot());
        bar.addView(shutter, new LinearLayout.LayoutParams(dp(78), dp(78)));

        status = new TextView(this);
        status.setText("Tap to focus");
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(13);
        status.setGravity(Gravity.END);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));

        rootView.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(rootView);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                Preview pv = new Preview.Builder().build();
                pv.setSurfaceProvider(preview.getSurfaceProvider());
                capture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                        .setTargetRotation(rotation)
                        .build();
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, capture);
                camera.getCameraControl().enableTorch(false);
            } catch (Exception e) {
                fail("Camera could not start: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (event.getRepeatCount() == 0) shoot();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void shoot() {
        if (busy || capture == null) return;
        busy = true;
        shutter.setAlpha(0.4f);
        status.setText("Saving…");
        capture.setTargetRotation(rotation);
        capture.takePicture(io, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                int deg;
                byte[] jpeg;
                try {
                    deg = image.getImageInfo().getRotationDegrees();
                    ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    jpeg = new byte[buf.remaining()];
                    buf.get(jpeg);
                } finally {
                    image.close();
                }
                try {
                    writeUpright(jpeg, deg);
                    runOnUiThread(() -> { setResult(RESULT_OK); finish(); });
                } catch (Throwable t) {
                    runOnUiThread(() -> fail("Photo not saved: " + t.getMessage()));
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                runOnUiThread(() -> {
                    busy = false;
                    shutter.setAlpha(1f);
                    status.setText("Didn't capture. Try again.");
                });
            }
        });
    }

    /** Writes the JPEG with its pixels upright. Unrotated shots are written untouched (no quality loss). */
    private void writeUpright(byte[] jpeg, int deg) throws Exception {
        File dir = out.getParentFile();
        if (dir != null) dir.mkdirs();
        if (deg == 0) {
            try (OutputStream os = new FileOutputStream(out)) { os.write(jpeg); }
            return;
        }
        Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (src == null) throw new IllegalStateException("could not read the photo");
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (rotated != src) src.recycle();
        try (OutputStream os = new FileOutputStream(out)) {
            rotated.compress(Bitmap.CompressFormat.JPEG, 95, os);
        }
        rotated.recycle();
    }

    private void fail(String msg) {
        setResult(RESULT_FIRST_USER, new Intent().putExtra(EXTRA_ERROR, msg));
        finish();
    }
}
