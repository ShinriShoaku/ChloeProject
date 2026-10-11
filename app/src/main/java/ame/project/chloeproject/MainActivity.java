package ame.project.chloeproject;
import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ame.project.chloeproject.Sensor.MouthAnalyzer;

public class MainActivity extends AppCompatActivity implements MouthAnalyzer.Listener {

    private static final int[] BG_COLORS = {0xFF00FF00, 0xFF000000, 0xFFFFFFFF, 0xFF0000FF, 0xFF808080};
    private static final String[] BG_NAMES = {"Hijau (chroma key)", "Hitam", "Putih", "Biru (chroma key)", "Abu-abu"};

    private static final String FILE_TALK = "img_talk.png";
    private static final String FILE_IDLE = "img_idle.png";
    private static final int MAX_IMG_SIZE = 1024;   // batasi agar hemat RAM di HP low end
    private static final long HOLD_MS = 120;        // tahan sebentar agar tidak berkedip

    private interface OnValue { void on(int value); }

    // View
    private FrameLayout root, stage;
    private ImageView imgIdle, imgTalk;
    private PreviewView previewView;
    private TextView tvDebug, tvSens, tvSize, tvIn, tvOut;
    private View panel;
    private SeekBar sbSens, sbSize, sbIn, sbOut;

    // State
    private SharedPreferences prefs;
    private ProcessCameraProvider cameraProvider;
    private MouthAnalyzer analyzer;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private boolean useFront = true;
    private boolean showPreview = false;
    private int bgIndex = 0;
    private boolean talking = false;
    private float smooth = 0f;
    private long lastOpenAt = 0;

    // Activity result
    private final ActivityResultLauncher<String> pickTalk = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) importImage(uri, FILE_TALK);
            });

    private final ActivityResultLauncher<String> pickIdle = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) importImage(uri, FILE_IDLE);
            });

    private final ActivityResultLauncher<String> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else Toast.makeText(this, "Izin kamera diperlukan", Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("cfg", MODE_PRIVATE);

        root = findViewById(R.id.root);
        stage = findViewById(R.id.stage);
        imgIdle = findViewById(R.id.imgIdle);
        imgTalk = findViewById(R.id.imgTalk);
        previewView = findViewById(R.id.previewView);
        tvDebug = findViewById(R.id.tvDebug);
        tvSens = findViewById(R.id.tvSens);
        tvSize = findViewById(R.id.tvSize);
        tvIn = findViewById(R.id.tvIn);
        tvOut = findViewById(R.id.tvOut);
        panel = findViewById(R.id.panel);
        sbSens = findViewById(R.id.sbSens);
        sbSize = findViewById(R.id.sbSize);
        sbIn = findViewById(R.id.sbIn);
        sbOut = findViewById(R.id.sbOut);

        // Load pengaturan tersimpan
        useFront = prefs.getBoolean("front", true);
        showPreview = prefs.getBoolean("preview", false);
        bgIndex = prefs.getInt("bg", 0);
        sbSens.setProgress(prefs.getInt("sens", 50));
        sbSize.setProgress(prefs.getInt("size", 80));   // 80 + 20 = 100%
        sbIn.setProgress(prefs.getInt("in", 120));
        sbOut.setProgress(prefs.getInt("out", 250));

        bindSeek(sbSens, v -> { prefs.edit().putInt("sens", v).apply(); updateLabels(); });
        bindSeek(sbSize, v -> { prefs.edit().putInt("size", v).apply(); applySize(); updateLabels(); });
        bindSeek(sbIn, v -> { prefs.edit().putInt("in", v).apply(); updateLabels(); });
        bindSeek(sbOut, v -> { prefs.edit().putInt("out", v).apply(); updateLabels(); });

        ((Button) findViewById(R.id.btnTalk)).setOnClickListener(v -> pickTalk.launch("image/*"));
        ((Button) findViewById(R.id.btnIdle)).setOnClickListener(v -> pickIdle.launch("image/*"));
        ((Button) findViewById(R.id.btnReset)).setOnClickListener(v -> {
            new File(getFilesDir(), FILE_TALK).delete();
            new File(getFilesDir(), FILE_IDLE).delete();
            applyImages();
        });
        ((Button) findViewById(R.id.btnCamera)).setOnClickListener(v -> {
            useFront = !useFront;
            prefs.edit().putBoolean("front", useFront).apply();
            bindCamera();
        });
        ((Button) findViewById(R.id.btnBg)).setOnClickListener(v -> {
            bgIndex = (bgIndex + 1) % BG_COLORS.length;
            prefs.edit().putInt("bg", bgIndex).apply();
            applyBg();
            Toast.makeText(this, BG_NAMES[bgIndex], Toast.LENGTH_SHORT).show();
        });
        ((Button) findViewById(R.id.btnPreview)).setOnClickListener(v -> {
            showPreview = !showPreview;
            prefs.edit().putBoolean("preview", showPreview).apply();
            bindCamera();
        });

        // Tap area animasi = sembunyikan/tampilkan panel (untuk tampilan bersih saat live)
        stage.setOnClickListener(v -> {
            boolean show = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(show ? View.VISIBLE : View.GONE);
            tvDebug.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!showPreview) previewView.setVisibility(View.GONE);
            else previewView.setVisibility(show ? View.VISIBLE : View.GONE);
        });

        analyzer = new MouthAnalyzer(this);

        applyBg();
        applySize();
        applyImages();
        updateLabels();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    // ---------------------------------------------------------------- Kamera

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                Toast.makeText(this, "Kamera gagal: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        CameraSelector selector = useFront
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;

        // Resolusi kecil = deteksi cepat di HP low end
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(240, 320))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, analyzer);

        try {
            if (showPreview) {
                Preview preview = new Preview.Builder()
                        .setTargetResolution(new Size(240, 320))
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                previewView.setVisibility(View.VISIBLE);
                cameraProvider.bindToLifecycle(this, selector, preview, analysis);
            } else {
                // Tanpa Preview use case -> lebih hemat baterai & CPU
                previewView.setVisibility(View.GONE);
                cameraProvider.bindToLifecycle(this, selector, analysis);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Gagal bind kamera: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------- Deteksi -> animasi

    @Override
    public void onMouth(boolean faceFound, float openness) {
        // Smoothing ringan agar tidak jitter
        smooth = faceFound ? (smooth * 0.5f + openness * 0.5f) : 0f;

        // Sensitivitas 0..100 -> ambang buka 0.25 .. 0.05
        float openT = 0.25f - 0.002f * sbSens.getProgress();
        float closeT = openT * 0.6f;   // hysteresis

        long now = SystemClock.uptimeMillis();
        boolean above = faceFound && smooth > (talking ? closeT : openT);

        if (above) {
            lastOpenAt = now;
            if (!talking) setTalking(true);
        } else if (talking && now - lastOpenAt > HOLD_MS) {
            setTalking(false);
        }

        if (panel.getVisibility() == View.VISIBLE) {
            tvDebug.setText(faceFound
                    ? String.format("Mulut: %.2f  (ambang %.2f)  %s", smooth, openT, talking ? "● BICARA" : "○ diam")
                    : "Wajah tidak terdeteksi");
        }
    }

    private void setTalking(boolean t) {
        talking = t;
        imgTalk.animate().cancel();
        if (t) {
            imgTalk.setScaleX(0.93f);
            imgTalk.setScaleY(0.93f);
            imgTalk.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(sbIn.getProgress()).start();
        } else {
            imgTalk.animate().alpha(0f).setDuration(sbOut.getProgress()).start();
        }
    }

    // ------------------------------------------------------------------ Gambar

    private void importImage(Uri uri, String fileName) {
        ioExecutor.execute(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / sample > MAX_IMG_SIZE) sample *= 2;

                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(in, null, o);
                }
                if (bmp == null) throw new Exception("Gambar tidak bisa dibaca");

                // Simpan sebagai PNG (transparansi tetap terjaga)
                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), fileName))) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                bmp.recycle();
                runOnUiThread(this::applyImages);
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Gagal: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void applyImages() {
        File ft = new File(getFilesDir(), FILE_TALK);
        File fi = new File(getFilesDir(), FILE_IDLE);

        Bitmap talk = ft.exists() ? BitmapFactory.decodeFile(ft.getPath()) : null;
        Bitmap idle = fi.exists() ? BitmapFactory.decodeFile(fi.getPath()) : null;

        boolean talkCustom = talk != null;
        imgTalk.setImageBitmap(talkCustom ? talk : placeholder(true));

        if (idle != null) {
            imgIdle.setImageBitmap(idle);
        } else if (!talkCustom) {
            imgIdle.setImageBitmap(placeholder(false));   // demo bawaan
        } else {
            imgIdle.setImageDrawable(null);               // hanya gambar bicara yang tampil
        }
    }

    /** Gambar bawaan sederhana agar app langsung bisa dicoba. */
    private Bitmap placeholder(boolean open) {
        Bitmap b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFD54F);
        c.drawCircle(256, 256, 240, p);
        p.setColor(0xFF333333);
        c.drawCircle(180, 200, 24, p);
        c.drawCircle(332, 200, 24, p);
        if (open) {
            c.drawOval(new RectF(196, 300, 316, 420), p);
        } else {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(190, 360, 322, 360, p);
        }
        return b;
    }

    // ---------------------------------------------------------------- UI helper

    private void applyBg() {
        root.setBackgroundColor(BG_COLORS[bgIndex]);
    }

    private void applySize() {
        float s = (sbSize.getProgress() + 20) / 100f;
        stage.setScaleX(s);
        stage.setScaleY(s);
    }

    private void updateLabels() {
        tvSens.setText("Sensitivitas mulut: " + sbSens.getProgress() + "  (naikkan jika animasi sulit muncul)");
        tvSize.setText("Ukuran gambar: " + (sbSize.getProgress() + 20) + "%");
        tvIn.setText("Fade in: " + sbIn.getProgress() + " ms");
        tvOut.setText("Fade out: " + sbOut.getProgress() + " ms");
    }

    private void bindSeek(SeekBar sb, OnValue cb) {
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { cb.on(p); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) cameraProvider.unbindAll();
        analyzer.close();
        cameraExecutor.shutdown();
        ioExecutor.shutdown();
    }
}