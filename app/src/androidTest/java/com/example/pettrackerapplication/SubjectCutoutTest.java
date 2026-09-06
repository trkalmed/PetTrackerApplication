package com.example.pettrackerapplication;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Whether a segmentation result is a photograph of an animal or an empty frame.
 *
 * "No subject found" does not arrive as null. The segmenter returns a bitmap the
 * size of the original with every non-subject pixel made transparent, so a frame
 * it recognises nothing in comes back correctly sized, correctly formatted and
 * invisible. Saving that as the pet's photo produced a picture that existed in
 * the database, loaded without error, and showed nothing on any screen.
 *
 * Bitmap is a framework class with no behaviour off-device, so these run on the
 * phone rather than the JVM.
 */
@RunWith(AndroidJUnit4.class)
public class SubjectCutoutTest {

    private static Bitmap transparent(int width, int height) {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    /** A filled rectangle covering {@code fraction} of the frame's area. */
    private static Bitmap withSubject(int width, int height, double fraction) {
        Bitmap bitmap = transparent(width, height);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        int side = (int) Math.round(Math.sqrt(fraction * width * height));
        canvas.drawRect(0, 0, side, side, paint);
        return bitmap;
    }

    @Test
    public void anEmptyCutoutIsNotASubject() {
        assertFalse(AddPetActivity.hasVisibleSubject(transparent(1200, 900)));
    }

    @Test
    public void aCatSizedCutoutIsASubject() {
        // A pet fills a good part of a photo somebody took of it.
        assertTrue(AddPetActivity.hasVisibleSubject(withSubject(1200, 900, 0.25)));
    }

    @Test
    public void aSmallButRealSubjectIsKept() {
        // An animal further away is still an animal. The threshold exists to
        // catch nothing at all, not to insist on a close-up.
        assertTrue(AddPetActivity.hasVisibleSubject(withSubject(1200, 900, 0.05)));
    }

    @Test
    public void aFewStraySpecklesAreNotASubject() {
        // What a failed segmentation actually leaves behind: a scattering of
        // edge pixels, far too little to be a photograph of anything.
        Bitmap bitmap = transparent(1200, 900);
        for (int i = 0; i < 40; i++) {
            bitmap.setPixel(i * 7, i * 5, Color.RED);
        }

        assertFalse(AddPetActivity.hasVisibleSubject(bitmap));
    }

    @Test
    public void anAlmostTransparentCutoutIsNotASubject() {
        // Alpha 8 out of 255 is invisible on any background. A cut-out made only
        // of that is an empty picture with extra steps.
        Bitmap bitmap = transparent(1200, 900);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.argb(8, 255, 0, 0));
        canvas.drawRect(0, 0, 600, 450, paint);

        assertFalse(AddPetActivity.hasVisibleSubject(bitmap));
    }

    @Test
    public void aPhotoWithNoAlphaChannelIsAlwaysASubject() {
        // RGB_565 cannot record transparency, so nothing was cut away and there
        // is nothing to be empty. Sampling it would find every pixel opaque
        // anyway; returning early only avoids reading the whole frame to learn
        // something already known.
        Bitmap opaque = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565);

        assertTrue(AddPetActivity.hasVisibleSubject(opaque));
    }

    @Test
    public void aDegenerateBitmapIsNotASubject() {
        assertFalse(AddPetActivity.hasVisibleSubject(transparent(1, 1)));
    }
}
