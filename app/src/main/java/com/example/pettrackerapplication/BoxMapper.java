package com.example.pettrackerapplication;


public final class BoxMapper {

    public enum ScaleMode {
        FIT_CENTER,
        FILL_CENTER
    }

    private BoxMapper() {
    }

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
