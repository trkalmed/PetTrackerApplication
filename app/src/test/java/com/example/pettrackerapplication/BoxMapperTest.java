package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Box mapping from detector pixels to view pixels.
 *
 * Pins down the bug this replaced: the overlay scaled x and y independently,
 * which is only correct when the view happens to share the image's aspect ratio.
 * On a 16:9 frame in a 9:16 portrait view — the ordinary case — every box came
 * out offset and the wrong shape, which reads as "the detector is bad" rather
 * than "the drawing is wrong".
 */
public class BoxMapperTest {

    private static final float EPS = 0.01f;

    /** A 1280x720 frame in a 1080x1920 portrait view — the real phone case. */
    private static final int IMG_W = 1280, IMG_H = 720;
    private static final float VIEW_W = 1080, VIEW_H = 1920;

    private static final int LEFT = 0, TOP = 1, RIGHT = 2, BOTTOM = 3;

    @Test
    public void fitCenterKeepsTheWholeImageAndCentresTheLetterbox() {
        // Uniform scale limited by width: 1080/1280 = 0.84375.
        // Scaled height 720 * 0.84375 = 607.5, so bars of (1920-607.5)/2 = 656.25.
        float[] m = BoxMapper.map(0, 0, IMG_W, IMG_H, IMG_W, IMG_H, VIEW_W, VIEW_H,
                BoxMapper.ScaleMode.FIT_CENTER);

        assertEquals(0f, m[LEFT], EPS);
        assertEquals(VIEW_W, m[RIGHT], EPS);
        assertEquals(656.25f, m[TOP], EPS);
        assertEquals(656.25f + 607.5f, m[BOTTOM], EPS);
    }

    @Test
    public void fillCenterCoversTheViewAndCropsEvenly() {
        // Scale limited by height: 1920/720 = 2.6667, so the scaled width is
        // 3413.3 and 1166.7 gets cropped, half off each side.
        float[] m = BoxMapper.map(0, 0, IMG_W, IMG_H, IMG_W, IMG_H, VIEW_W, VIEW_H,
                BoxMapper.ScaleMode.FILL_CENTER);

        assertEquals(0f, m[TOP], EPS);
        assertEquals(VIEW_H, m[BOTTOM], EPS);
        assertTrue("left edge should be cropped off-screen", m[LEFT] < 0);
        assertEquals("crop must be even on both sides",
                -m[LEFT], m[RIGHT] - VIEW_W, EPS);
    }

    @Test
    public void aspectRatioIsPreservedInBothModes() {
        // A square in the image must stay square on screen. Two-axis scaling
        // stretched it, which is exactly what made the boxes look wrong.
        for (BoxMapper.ScaleMode mode : BoxMapper.ScaleMode.values()) {
            float[] m = BoxMapper.map(100, 100, 300, 300,
                    IMG_W, IMG_H, VIEW_W, VIEW_H, mode);
            assertEquals("square must stay square in " + mode,
                    m[RIGHT] - m[LEFT], m[BOTTOM] - m[TOP], EPS);
        }
    }

    @Test
    public void theImageCentreLandsOnTheViewCentreInBothModes() {
        for (BoxMapper.ScaleMode mode : BoxMapper.ScaleMode.values()) {
            float[] m = BoxMapper.map(IMG_W / 2f, IMG_H / 2f, IMG_W / 2f, IMG_H / 2f,
                    IMG_W, IMG_H, VIEW_W, VIEW_H, mode);
            assertEquals("centre x in " + mode, VIEW_W / 2f, m[LEFT], EPS);
            assertEquals("centre y in " + mode, VIEW_H / 2f, m[TOP], EPS);
        }
    }

    @Test
    public void aMatchingAspectRatioMapsOneToOneInBothModes() {
        // With view and image in agreement there is nothing to letterbox or
        // crop, so both modes must collapse to the same plain scale.
        float[] fit = BoxMapper.map(320, 180, 640, 360, IMG_W, IMG_H, 640, 360,
                BoxMapper.ScaleMode.FIT_CENTER);
        float[] fill = BoxMapper.map(320, 180, 640, 360, IMG_W, IMG_H, 640, 360,
                BoxMapper.ScaleMode.FILL_CENTER);

        assertEquals(fit[LEFT], fill[LEFT], EPS);
        assertEquals(fit[TOP], fill[TOP], EPS);
        assertEquals(160f, fit[LEFT], EPS);   // 320 * 0.5
        assertEquals(90f, fit[TOP], EPS);     // 180 * 0.5
    }

    @Test
    public void theOldTwoAxisScalingWouldHaveBeenWrongHere() {
        // Documents the regression directly: under the old maths a box at the
        // image centre mapped to (viewW/2, viewH/2) only by accident of the
        // aspect ratios. Its vertical scale was 1920/720 = 2.667 while the
        // horizontal was 1080/1280 = 0.844 — a 3.2x distortion.
        float oldScaleX = VIEW_W / IMG_W;
        float oldScaleY = VIEW_H / IMG_H;
        assertTrue("the two axes must genuinely disagree for this to matter",
                Math.abs(oldScaleX - oldScaleY) > 1.5f);

        // In FILL mode the uniform scale happens to equal the old vertical one,
        // so the error shows up horizontally: the old maths squeezed a 200px
        // box to 168.75 while the correct mapping gives 533.3.
        float[] m = BoxMapper.map(100, 100, 300, 300,
                IMG_W, IMG_H, VIEW_W, VIEW_H, BoxMapper.ScaleMode.FILL_CENTER);

        float oldWidth = (300 - 100) * oldScaleX;
        float newWidth = m[RIGHT] - m[LEFT];

        assertEquals(168.75f, oldWidth, EPS);
        assertEquals(533.33f, newWidth, 0.1f);
        assertTrue("the corrected width must differ from the old stretched one",
                Math.abs(newWidth - oldWidth) > 1f);
    }

    @Test
    public void aZeroSizedImageDoesNotDivideByZero() {
        float[] m = BoxMapper.map(0, 0, 10, 10, 0, 0, VIEW_W, VIEW_H,
                BoxMapper.ScaleMode.FIT_CENTER);

        assertEquals(0f, m[LEFT], EPS);
        assertEquals(0f, m[BOTTOM], EPS);
    }
}
