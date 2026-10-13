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

@RunWith(AndroidJUnit4.class)
public class SubjectCutoutTest {

    private static Bitmap transparent(int width, int height) {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

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
        assertTrue(AddPetActivity.hasVisibleSubject(withSubject(1200, 900, 0.25)));
    }

    @Test
    public void aSmallButRealSubjectIsKept() {
        assertTrue(AddPetActivity.hasVisibleSubject(withSubject(1200, 900, 0.05)));
    }

    @Test
    public void aFewStraySpecklesAreNotASubject() {
        Bitmap bitmap = transparent(1200, 900);
        for (int i = 0; i < 40; i++) {
            bitmap.setPixel(i * 7, i * 5, Color.RED);
        }

        assertFalse(AddPetActivity.hasVisibleSubject(bitmap));
    }

    @Test
    public void anAlmostTransparentCutoutIsNotASubject() {
        Bitmap bitmap = transparent(1200, 900);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.argb(8, 255, 0, 0));
        canvas.drawRect(0, 0, 600, 450, paint);

        assertFalse(AddPetActivity.hasVisibleSubject(bitmap));
    }

    @Test
    public void aPhotoWithNoAlphaChannelIsAlwaysASubject() {
        Bitmap opaque = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565);

        assertTrue(AddPetActivity.hasVisibleSubject(opaque));
    }

    @Test
    public void aDegenerateBitmapIsNotASubject() {
        assertFalse(AddPetActivity.hasVisibleSubject(transparent(1, 1)));
    }
}
