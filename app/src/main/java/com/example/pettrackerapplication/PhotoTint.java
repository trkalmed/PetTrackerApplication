package com.example.pettrackerapplication;

import android.content.res.ColorStateList;
import android.widget.ImageView;

import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

/** Clears the placeholder tint before a photo is shown. */
final class PhotoTint {

    private PhotoTint() {}

    static void clearFor(ImageView view) {
        ImageViewCompat.setImageTintList(view, null);
    }

    static void restore(ImageView view, @ColorRes int colour) {
        ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(
                ContextCompat.getColor(view.getContext(), colour)));
    }
}
