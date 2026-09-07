package com.example.pettrackerapplication;

import android.content.res.ColorStateList;
import android.widget.ImageView;

import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

/**
 * Turns the placeholder's tint off before a photograph goes into the same view.
 *
 * Every view that shows a pet's picture also shows a paw when there is none, and
 * the paw is a vector whose fill is transparent — it is drawn entirely by the
 * `app:tint` in the layout. That tint is a property of the *view*, not of the
 * drawable, so it kept being applied when the photograph arrived: the cat's
 * shape came out correctly and every pixel of it was painted one flat colour. On
 * a dark theme that is a dark green silhouette on a dark green card, which is
 * indistinguishable from the photo having failed to load.
 *
 * So the tint is cleared whenever a photograph is shown and put back whenever the
 * paw returns. Clearing it in the layout instead is not an option: with no tint
 * the placeholder is invisible.
 */
final class PhotoTint {

    private PhotoTint() {}

    /** Call before loading a photograph into {@code view}. */
    static void clearFor(ImageView view) {
        ImageViewCompat.setImageTintList(view, null);
    }

    /** Call when {@code view} goes back to showing the paw. */
    static void restore(ImageView view, @ColorRes int colour) {
        ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(
                ContextCompat.getColor(view.getContext(), colour)));
    }
}
