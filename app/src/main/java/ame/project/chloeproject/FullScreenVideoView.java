package ame.project.chloeproject;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.VideoView;

/**
 * Custom VideoView yang mengukur ukurannya agar selalu memenuhi seluruh layar (Center Crop / Fullscreen).
 */
public class FullScreenVideoView extends VideoView {

    private int videoWidth = 0;
    private int videoHeight = 0;

    public FullScreenVideoView(Context context) {
        super(context);
    }

    public FullScreenVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FullScreenVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setVideoSize(int width, int height) {
        if (width > 0 && height > 0 && (this.videoWidth != width || this.videoHeight != height)) {
            this.videoWidth = width;
            this.videoHeight = height;
            requestLayout();
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int initialWidth = getDefaultSize(0, widthMeasureSpec);
        int initialHeight = getDefaultSize(0, heightMeasureSpec);
        int finalWidth = initialWidth;
        int finalHeight = initialHeight;

        if (videoWidth > 0 && videoHeight > 0) {
            if (videoWidth * initialHeight > initialWidth * videoHeight) {
                // Aspect ratio video lebih lebar dari layar -> skala lebar agar memenuhi tinggi
                finalWidth = initialHeight * videoWidth / videoHeight;
            } else {
                // Aspect ratio video lebih tinggi/sama dengan layar -> skala tinggi agar memenuhi lebar
                finalHeight = initialWidth * videoHeight / videoWidth;
            }
        }

        setMeasuredDimension(finalWidth, finalHeight);
    }
}
