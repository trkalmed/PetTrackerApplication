package com.example.pettrackerapplication;

import android.content.Context;
import android.content.res.ColorStateList;

import androidx.annotation.ColorRes;
import androidx.annotation.IdRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Tints the bottom navigation to the destination you are on.
 *
 * A colour-state-list cannot do this on its own: a selector knows that
 * <em>something</em> is checked, not <em>which</em> item, so every tab would
 * share one accent. Rebuilding the list per selection is what gives each
 * destination its own colour.
 *
 * Lives here rather than in an activity because two screens own a nav bar —
 * the dashboard and the Food tab — and a second copy would drift.
 */
public final class NavTint {

    /** Alpha for the pill behind the selected icon. Enough to read, not to shout. */
    private static final int INDICATOR_ALPHA = 60;

    private NavTint() {
        // static helper, no instances
    }

    @ColorRes
    private static int accentFor(@IdRes int itemId) {
        if (itemId == R.id.nav_live) return R.color.tab_live;
        if (itemId == R.id.nav_food) return R.color.tab_food;
        if (itemId == R.id.nav_settings) return R.color.tab_settings;
        return R.color.tab_home;
    }

    /**
     * Applies the accent for {@code itemId}. Call once with the starting
     * destination and again whenever the selection changes, or the bar keeps
     * the previous tab's colour.
     */
    public static void apply(Context context, BottomNavigationView nav, @IdRes int itemId) {
        if (nav == null) return;

        int accent = ContextCompat.getColor(context, accentFor(itemId));
        int idle = ContextCompat.getColor(context, R.color.text_secondary);

        ColorStateList tint = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[0]},
                new int[]{accent, idle});

        nav.setItemIconTintList(tint);
        nav.setItemTextColor(tint);
        nav.setItemActiveIndicatorColor(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, INDICATOR_ALPHA)));
    }
}
