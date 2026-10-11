package ame.project.chloeproject.Sensor;

import android.graphics.PointF;
import android.media.Image;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;

/**
 * Mendeteksi seberapa terbuka mulut memakai kontur bibir ML Kit.
 *
 * openness = jarak (bibir atas bagian dalam <-> bibir bawah bagian dalam) / lebar mulut
 * Dinormalisasi dengan lebar mulut, jadi tidak terpengaruh jarak wajah ke kamera
 * dan tidak terpengaruh rotasi kepala (memakai jarak Euclidean).
 */
public class MouthAnalyzer implements ImageAnalysis.Analyzer {

    public interface Listener {
        /** Dipanggil di main thread. */
        void onMouth(boolean faceFound, float openness);
    }

    // ~20 fps maksimal, hemat CPU untuk HP low end
    private static final long MIN_INTERVAL_MS = 50;

    private final FaceDetector detector;
    private final Listener listener;
    private long lastTs = 0;

    public MouthAnalyzer(Listener listener) {
        this.listener = listener;
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL) // butuh kontur bibir
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.2f)
                .build();
        detector = FaceDetection.getClient(options);
    }

    @Override
    @OptIn(markerClass = ExperimentalGetImage.class)
    public void analyze(@NonNull ImageProxy imageProxy) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTs < MIN_INTERVAL_MS) {
            imageProxy.close();
            return;
        }
        lastTs = now;

        Image media = imageProxy.getImage();
        if (media == null) {
            imageProxy.close();
            return;
        }

        InputImage input = InputImage.fromMediaImage(
                media, imageProxy.getImageInfo().getRotationDegrees());

        detector.process(input)
                .addOnSuccessListener(faces -> handleFaces(faces))
                .addOnFailureListener(e -> listener.onMouth(false, 0f))
                .addOnCompleteListener(t -> imageProxy.close());
    }

    private void handleFaces(List<Face> faces) {
        if (faces == null || faces.isEmpty()) {
            listener.onMouth(false, 0f);
            return;
        }

        // Ambil wajah terbesar
        Face best = faces.get(0);
        int bestArea = 0;
        for (Face f : faces) {
            int area = f.getBoundingBox().width() * f.getBoundingBox().height();
            if (area > bestArea) {
                bestArea = area;
                best = f;
            }
        }

        FaceContour upperTop = best.getContour(FaceContour.UPPER_LIP_TOP);
        FaceContour upperBottom = best.getContour(FaceContour.UPPER_LIP_BOTTOM);
        FaceContour lowerTop = best.getContour(FaceContour.LOWER_LIP_TOP);

        if (upperTop == null || upperBottom == null || lowerTop == null
                || upperTop.getPoints().size() < 2
                || upperBottom.getPoints().isEmpty()
                || lowerTop.getPoints().isEmpty()) {
            listener.onMouth(false, 0f);
            return;
        }

        List<PointF> top = upperTop.getPoints();
        float mouthWidth = dist(top.get(0), top.get(top.size() - 1));
        if (mouthWidth < 1f) {
            listener.onMouth(false, 0f);
            return;
        }

        float gap = dist(centroid(upperBottom.getPoints()), centroid(lowerTop.getPoints()));
        listener.onMouth(true, gap / mouthWidth);
    }

    private static PointF centroid(List<PointF> pts) {
        float x = 0, y = 0;
        for (PointF p : pts) {
            x += p.x;
            y += p.y;
        }
        int n = pts.size();
        return new PointF(x / n, y / n);
    }

    private static float dist(PointF a, PointF b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public void close() {
        detector.close();
    }
}