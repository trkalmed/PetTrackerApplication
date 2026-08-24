package com.example.pettrackerapplication;

/**
 * Maps detector boxes from image pixels to view pixels.
 *
 * Plain arithmetic on floats, deliberately free of {@code RectF} and any other
 * Android type, so it runs in an ordinary JVM unit test. This is where the
 * overlay used to be wrong, and the failure mode — boxes near the right place
 * but subtly offset and the wrong shape — is very easy to miss by eye and very
 * easy to blame on the model instead.
 *
 * A view never distorts the image: it scales uniformly, then either letterboxes
 * the remainder or crops the overflow. So there is one scale factor, not one per
 * axis, plus a centring offset that simply goes negative when cropping.
 */
public final class BoxMapper {

    /** How the view beneath the overlay fits the image. */
    public enum ScaleMode {
        /** Whole image visible, bars on the short side — the Pi's ImageView. */
        FIT_CENTER,
        /** Image fills the view, overflow cropped — PreviewView's default. */
        FILL_CENTER
    }

    private BoxMapper() {
        // static helper, no instances
    }

    /**
     * @return {left, top, right, bottom} in view pixels
     */
    public static float[] map(float left, float top, float right, float bottom,
                              int imageWidth, int imageHeight,
                              float viewWidth, float viewHeight,
                              ScaleMode mode) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return new float[]{0f, 0f, 0f, 0f};
        }

        float scale = scaleFor(imageWidth, imageHeight, viewWidth, viewHeight, mode);
        float offsetX = (viewWidth - imageWidth * scale) / 2f;
        float offsetY = (viewHeight - imageHeight * scale) / 2f;

        return new float[]{
                left * scale + offsetX,
                top * scale + offsetY,
                right * scale + offsetX,
                bottom * scale + offsetY
        };
    }

    static float scaleFor(int imageWidth, int imageHeight,
                          float viewWidth, float viewHeight, ScaleMode mode) {
        float scaleX = viewWidth / imageWidth;
        float scaleY = viewHeight / imageHeight;
        return mode == ScaleMode.FIT_CENTER
                ? Math.min(scaleX, scaleY)
                : Math.max(scaleX, scaleY);
    }
}
