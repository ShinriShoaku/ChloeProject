package ame.project.chloeproject;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
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
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ame.project.chloeproject.Sensor.MouthAnalyzer;

/**
 * Aktivitas utama untuk Chloe Project.
 * Menampilkan avatar 2D yang dapat merespons gerak bibir dari kamera secara real-time.
 */
public class MainActivity extends AppCompatActivity implements MouthAnalyzer.Listener {

    // Konstanta Latar Belakang
    public static final int[] BG_COLORS = {0xFF00FF00, 0xFF000000, 0xFFFFFFFF, 0xFF0000FF, 0xFF808080};
    public static final String[] BG_NAMES = {"Hijau (chroma key)", "Hitam", "Putih", "Biru (chroma key)", "Abu-abu"};

    public static final int BG_MODE_COLOR = 0;
    public static final int BG_MODE_IMAGE = 1;
    public static final int BG_MODE_VIDEO = 2;

    // Nama File Penyimpanan
    public static final String FILE_TALK = "img_talk.png";
    public static final String FILE_IDLE = "img_idle.png";
    public static final String FILE_BG_IMG = "bg_image.png";
    public static final String FILE_BG_VID = "bg_video.mp4";

    private static final int MAX_IMG_SIZE = 1024;
    private static final long HOLD_MS = 120;
    private static final float CLICK_THRESHOLD = 15f;

    private interface OnSeekBarChangeCallback { void onChange(int value); }

    // Views Utama
    private FrameLayout root;
    private FrameLayout stage;
    private ImageView imgIdle;
    private ImageView imgTalk;
    private ImageView bgImageView;
    private FullScreenVideoView bgVideoView;
    private PreviewView previewView;
    private TextView tvDebug;

    // View BottomSheet Pengaturan
    private BottomSheetDialog settingsDialog;
    private TextView tvSens, tvSize, tvIn, tvOut, tvBgInfo;
    private SeekBar sbSens, sbSize, sbIn, sbOut;

    // Helpers & Managers
    private AppPreferences prefs;
    private ProcessCameraProvider cameraProvider;
    private MouthAnalyzer analyzer;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    // State
    private boolean talking = false;
    private float smooth = 0f;
    private long lastOpenAt = 0;

    // Touch Drag State
    private float dX, dY;
    private float startX, startY;
    private boolean isDragging = false;

    // Activity Result Launchers
    private final ActivityResultLauncher<String> pickTalk = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) importImage(uri, FILE_TALK);
            });

    private final ActivityResultLauncher<String> pickIdle = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) importImage(uri, FILE_IDLE);
            });

    private final ActivityResultLauncher<String> pickBgImage = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) importBgImage(uri);
            });

    private final ActivityResultLauncher<String> pickBgVideo = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) importBgVideo(uri);
            });

    private final ActivityResultLauncher<String> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Izin kamera diperlukan", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        hideSystemUI();

        prefs = new AppPreferences(this);
        analyzer = new MouthAnalyzer(this);

        initViews();
        restoreAvatarPosition();
        setupStageTouch();

        applyBg();
        applySize();
        applyImages();

        checkAndStartCamera();
    }

    private void initViews() {
        root = findViewById(R.id.root);
        stage = findViewById(R.id.stage);
        imgIdle = findViewById(R.id.imgIdle);
        imgTalk = findViewById(R.id.imgTalk);
        bgImageView = findViewById(R.id.bgImageView);
        bgVideoView = findViewById(R.id.bgVideoView);
        previewView = findViewById(R.id.previewView);
        tvDebug = findViewById(R.id.tvDebug);

        ImageButton btnOpenSettings = findViewById(R.id.btnOpenSettings);
        btnOpenSettings.setOnClickListener(v -> showSettingsDialog());
    }

    private void restoreAvatarPosition() {
        stage.setTranslationX(prefs.getPosX());
        stage.setTranslationY(prefs.getPosY());
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupStageTouch() {
        stage.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX = stage.getTranslationX() - event.getRawX();
                    dY = stage.getTranslationY() - event.getRawY();
                    startX = event.getRawX();
                    startY = event.getRawY();
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float diffX = Math.abs(event.getRawX() - startX);
                    float diffY = Math.abs(event.getRawY() - startY);
                    if (diffX > CLICK_THRESHOLD || diffY > CLICK_THRESHOLD) {
                        isDragging = true;
                        stage.setTranslationX(event.getRawX() + dX);
                        stage.setTranslationY(event.getRawY() + dY);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (isDragging) {
                        prefs.setPosition(stage.getTranslationX(), stage.getTranslationY());
                    } else {
                        v.performClick();
                        showSettingsDialog();
                    }
                    return true;
            }
            return false;
        });
    }

    private void checkAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    // -------------------------------------------------------- Pengaturan (BottomSheet)

    private void showSettingsDialog() {
        if (settingsDialog == null) {
            settingsDialog = new BottomSheetDialog(this);
            View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_settings, root, false);
            settingsDialog.setContentView(sheetView);

            // Bind views BottomSheet
            tvSens = sheetView.findViewById(R.id.tvSens);
            tvSize = sheetView.findViewById(R.id.tvSize);
            tvIn = sheetView.findViewById(R.id.tvIn);
            tvOut = sheetView.findViewById(R.id.tvOut);
            tvBgInfo = sheetView.findViewById(R.id.tvBgInfo);

            sbSens = sheetView.findViewById(R.id.sbSens);
            sbSize = sheetView.findViewById(R.id.sbSize);
            sbIn = sheetView.findViewById(R.id.sbIn);
            sbOut = sheetView.findViewById(R.id.sbOut);

            // Tombol Avatar
            sheetView.findViewById(R.id.btnTalk).setOnClickListener(v -> pickTalk.launch("image/*"));
            sheetView.findViewById(R.id.btnIdle).setOnClickListener(v -> pickIdle.launch("image/*"));
            sheetView.findViewById(R.id.btnReset).setOnClickListener(v -> resetAvatarImages());

            // Tombol BG
            sheetView.findViewById(R.id.btnBgColor).setOnClickListener(v -> cycleBgColor());
            sheetView.findViewById(R.id.btnBgImage).setOnClickListener(v -> pickBgImage.launch("image/*"));
            sheetView.findViewById(R.id.btnBgVideo).setOnClickListener(v -> pickBgVideo.launch("video/*"));
            sheetView.findViewById(R.id.btnBgReset).setOnClickListener(v -> resetBg());

            // Tombol Kamera
            sheetView.findViewById(R.id.btnCamera).setOnClickListener(v -> switchCamera());
            sheetView.findViewById(R.id.btnPreview).setOnClickListener(v -> toggleCameraPreview());

            // Tombol Reset Posisi
            sheetView.findViewById(R.id.btnResetPos).setOnClickListener(v -> resetAvatarPosition());

            // SeekBars
            sbSens.setProgress(prefs.getSensitivity());
            sbSize.setProgress(prefs.getSize());
            sbIn.setProgress(prefs.getFadeIn());
            sbOut.setProgress(prefs.getFadeOut());

            bindSeek(sbSens, value -> { prefs.setSensitivity(value); updateLabels(); });
            bindSeek(sbSize, value -> { prefs.setSize(value); applySize(); updateLabels(); });
            bindSeek(sbIn, value -> { prefs.setFadeIn(value); updateLabels(); });
            bindSeek(sbOut, value -> { prefs.setFadeOut(value); updateLabels(); });

            settingsDialog.setOnDismissListener(dialog -> hideSystemUI());
        }

        updateLabels();
        updateBgInfo();
        settingsDialog.show();
    }

    private void resetAvatarImages() {
        new File(getFilesDir(), FILE_TALK).delete();
        new File(getFilesDir(), FILE_IDLE).delete();
        applyImages();
        Toast.makeText(this, "Gambar avatar diringkas ke default", Toast.LENGTH_SHORT).show();
    }

    private void cycleBgColor() {
        prefs.setBgMode(BG_MODE_COLOR);
        int nextBgIndex = (prefs.getBgIndex() + 1) % BG_COLORS.length;
        prefs.setBgIndex(nextBgIndex);
        applyBg();
        updateBgInfo();
    }

    private void resetBg() {
        prefs.setBgMode(BG_MODE_COLOR);
        prefs.setBgIndex(0);
        new File(getFilesDir(), FILE_BG_IMG).delete();
        new File(getFilesDir(), FILE_BG_VID).delete();
        applyBg();
        updateBgInfo();
        Toast.makeText(this, "Latar belakang direset ke warna hijau", Toast.LENGTH_SHORT).show();
    }

    private void switchCamera() {
        boolean newUseFront = !prefs.isUseFrontCamera();
        prefs.setUseFrontCamera(newUseFront);
        bindCamera();
    }

    private void toggleCameraPreview() {
        boolean newShowPreview = !prefs.isShowCameraPreview();
        prefs.setShowCameraPreview(newShowPreview);
        bindCamera();
    }

    private void resetAvatarPosition() {
        stage.setTranslationX(0f);
        stage.setTranslationY(0f);
        prefs.setPosition(0f, 0f);
        Toast.makeText(this, "Posisi avatar direset ke tengah", Toast.LENGTH_SHORT).show();
    }

    private void updateBgInfo() {
        if (tvBgInfo == null) return;
        int mode = prefs.getBgMode();
        if (mode == BG_MODE_IMAGE) {
            tvBgInfo.setText(R.string.bg_active_image);
        } else if (mode == BG_MODE_VIDEO) {
            tvBgInfo.setText(R.string.bg_active_video);
        } else {
            tvBgInfo.setText(getString(R.string.bg_active_color, BG_NAMES[prefs.getBgIndex()]));
        }
    }

    // -------------------------------------------------------- Latar Belakang (BG)

    private void applyBg() {
        int mode = prefs.getBgMode();
        if (mode == BG_MODE_IMAGE) {
            File f = new File(getFilesDir(), FILE_BG_IMG);
            if (f.exists()) {
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b != null) {
                    bgImageView.setImageBitmap(b);
                    bgImageView.setVisibility(View.VISIBLE);
                    if (bgVideoView.isPlaying()) bgVideoView.stopPlayback();
                    bgVideoView.setVisibility(View.GONE);
                    return;
                }
            }
        } else if (mode == BG_MODE_VIDEO) {
            File f = new File(getFilesDir(), FILE_BG_VID);
            if (f.exists()) {
                bgImageView.setVisibility(View.GONE);
                bgVideoView.setVisibility(View.VISIBLE);
                bgVideoView.setVideoPath(f.getPath());
                bgVideoView.setOnPreparedListener(mp -> {
                    mp.setLooping(true);
                    mp.setVolume(0f, 0f);
                    mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    if (mp.getVideoWidth() > 0 && mp.getVideoHeight() > 0) {
                        bgVideoView.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                    }
                    mp.setOnVideoSizeChangedListener((mp1, width, height) -> bgVideoView.setVideoSize(width, height));
                    bgVideoView.start();
                });
                return;
            }
        }

        // Mode Warna Default
        bgImageView.setVisibility(View.GONE);
        if (bgVideoView.isPlaying()) bgVideoView.stopPlayback();
        bgVideoView.setVisibility(View.GONE);
        root.setBackgroundColor(BG_COLORS[prefs.getBgIndex()]);
    }

    private void importBgImage(Uri uri) {
        ioExecutor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(new File(getFilesDir(), FILE_BG_IMG))) {
                if (in == null) throw new Exception("Tidak dapat membuka file gambar");
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                prefs.setBgMode(BG_MODE_IMAGE);
                runOnUiThread(() -> {
                    applyBg();
                    updateBgInfo();
                    Toast.makeText(this, "Gambar BG berhasil dimuat", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                FirebaseCrashlytics.getInstance().recordException(e);
                runOnUiThread(() -> Toast.makeText(this, "Gagal memuat gambar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void importBgVideo(Uri uri) {
        ioExecutor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(new File(getFilesDir(), FILE_BG_VID))) {
                if (in == null) throw new Exception("Tidak dapat membuka file video");
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                prefs.setBgMode(BG_MODE_VIDEO);
                runOnUiThread(() -> {
                    applyBg();
                    updateBgInfo();
                    Toast.makeText(this, "Video BG berhasil dimuat", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                FirebaseCrashlytics.getInstance().recordException(e);
                runOnUiThread(() -> Toast.makeText(this, "Gagal memuat video: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // -------------------------------------------------------- Kamera & CameraX

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                FirebaseCrashlytics.getInstance().recordException(e);
                Toast.makeText(this, "Kamera gagal: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressWarnings("deprecation")
    private void bindCamera() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        CameraSelector selector = prefs.isUseFrontCamera()
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, analyzer);

        try {
            if (prefs.isShowCameraPreview()) {
                Preview preview = new Preview.Builder()
                        .setTargetResolution(new Size(480, 640))
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                previewView.setVisibility(View.VISIBLE);
                cameraProvider.bindToLifecycle(this, selector, preview, analysis);
            } else {
                previewView.setVisibility(View.GONE);
                cameraProvider.bindToLifecycle(this, selector, analysis);
            }
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
            Toast.makeText(this, "Gagal bind kamera: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // -------------------------------------------------------- Deteksi Mulut -> Animasi

    @Override
    public void onMouth(boolean faceFound, float openness) {
        smooth = faceFound ? (smooth * 0.5f + openness * 0.5f) : 0f;

        float openT = 0.25f - 0.002f * prefs.getSensitivity();
        float closeT = openT * 0.6f;

        long now = SystemClock.uptimeMillis();
        boolean above = faceFound && smooth > (talking ? closeT : openT);

        if (above) {
            lastOpenAt = now;
            if (!talking) setTalking(true);
        } else if (talking && now - lastOpenAt > HOLD_MS) {
            setTalking(false);
        }

        if (tvDebug != null && tvDebug.getVisibility() == View.VISIBLE) {
            tvDebug.setText(faceFound
                    ? String.format(Locale.getDefault(), "Mulut: %.2f  (ambang %.2f)  %s", smooth, openT, talking ? "● BICARA" : "○ diam")
                    : "Wajah tidak terdeteksi");
        }
    }

    private void setTalking(boolean t) {
        talking = t;
        imgTalk.animate().cancel();
        if (t) {
            imgIdle.setVisibility(View.INVISIBLE);
            imgTalk.setVisibility(View.VISIBLE);
            imgTalk.setScaleX(0.93f);
            imgTalk.setScaleY(0.93f);
            int durationIn = prefs.getFadeIn();
            imgTalk.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(durationIn).start();
        } else {
            int durationOut = prefs.getFadeOut();
            imgTalk.animate().alpha(0f).setDuration(durationOut)
                    .withEndAction(() -> {
                        if (!talking) {
                            imgTalk.setVisibility(View.GONE);
                            imgIdle.setVisibility(View.VISIBLE);
                        }
                    }).start();
        }
    }

    // -------------------------------------------------------- Gambar Avatar

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

                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), fileName))) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                bmp.recycle();
                runOnUiThread(this::applyImages);
            } catch (Exception e) {
                FirebaseCrashlytics.getInstance().recordException(e);
                runOnUiThread(() -> Toast.makeText(this, "Gagal: " + e.getMessage(), Toast.LENGTH_LONG).show());
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
            imgIdle.setImageBitmap(placeholder(false));
        } else {
            imgIdle.setImageDrawable(null);
        }
    }

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

    // -------------------------------------------------------- UI Helpers & Lifecycle

    private void applySize() {
        int progress = prefs.getSize();
        float s = (progress + 20) / 100f;
        stage.setScaleX(s);
        stage.setScaleY(s);
    }

    @SuppressLint("SetTextI18n")
    private void updateLabels() {
        if (tvSens != null && sbSens != null) {
            tvSens.setText("Sensitivitas mulut: " + sbSens.getProgress() + "  (naikkan jika animasi sulit muncul)");
        }
        if (tvSize != null && sbSize != null) {
            tvSize.setText("Ukuran gambar: " + (sbSize.getProgress() + 20) + "%");
        }
        if (tvIn != null && sbIn != null) {
            tvIn.setText("Fade in: " + sbIn.getProgress() + " ms");
        }
        if (tvOut != null && sbOut != null) {
            tvOut.setText("Fade out: " + sbOut.getProgress() + " ms");
        }
    }

    private void bindSeek(SeekBar sb, OnSeekBarChangeCallback cb) {
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { cb.onChange(p); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (prefs.getBgMode() == BG_MODE_VIDEO && bgVideoView != null && !bgVideoView.isPlaying()) {
            applyBg();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bgVideoView != null && bgVideoView.isPlaying()) {
            bgVideoView.pause();
        }
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
