package com.example.pettrackerapplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsActivity extends ThemedActivity {

    private static final boolean DEFAULT_SOUND_ENABLED = true;
    private static final int     DEFAULT_SOUND_VOLUME  = 70;

    private SharedPreferences prefs;

    private MaterialCardView cardAccountManagement;
    private MaterialCardView cardPiConnection;
    private MaterialCardView cardContactSupport;
    private TextView         tvPiConnectionSummary;
    private MaterialSwitch   swSound;
    private SeekBar          sbSoundVolume;

    private MaterialCardView tileLight, tileDark, tileAmoled;
    private TextView         labelLight, labelDark, labelAmoled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.btnBack).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        prefs = Prefs.get(this);

        cardAccountManagement = findViewById(R.id.cardAccountManagement);
        cardPiConnection      = findViewById(R.id.cardPiConnection);
        cardContactSupport    = findViewById(R.id.cardContactSupport);
        tvPiConnectionSummary = findViewById(R.id.tvPiConnectionSummary);
        swSound               = findViewById(R.id.swSound);
        sbSoundVolume          = findViewById(R.id.sbSoundVolume);

        tileLight   = findViewById(R.id.tileLight);
        tileDark    = findViewById(R.id.tileDark);
        tileAmoled  = findViewById(R.id.tileAmoled);
        labelLight  = findViewById(R.id.labelLight);
        labelDark   = findViewById(R.id.labelDark);
        labelAmoled = findViewById(R.id.labelAmoled);

        setupAppearance();

        cardAccountManagement.setOnClickListener(v ->
                startActivity(new Intent(this, AccountActivity.class)));

        cardPiConnection.setOnClickListener(v -> showPiIpDialog());

        cardContactSupport.setOnClickListener(v -> openContactSupport());

        boolean soundEnabled = prefs.getBoolean(Prefs.KEY_SOUND_ENABLED, DEFAULT_SOUND_ENABLED);
        int     soundVolume  = prefs.getInt(Prefs.KEY_SOUND_VOLUME, DEFAULT_SOUND_VOLUME);

        swSound.setChecked(soundEnabled);
        sbSoundVolume.setProgress(soundVolume);
        sbSoundVolume.setEnabled(soundEnabled);

        swSound.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(Prefs.KEY_SOUND_ENABLED, isChecked).apply();
            sbSoundVolume.setEnabled(isChecked);
        });

        sbSoundVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(Prefs.KEY_SOUND_VOLUME, seekBar.getProgress()).apply();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePiConnectionSummary();
        checkPiConnection();
    }


    private void setupAppearance() {
        findViewById(R.id.themeLight).setOnClickListener(
                v -> chooseTheme(ThemeManager.LIGHT));
        findViewById(R.id.themeDark).setOnClickListener(
                v -> chooseTheme(ThemeManager.DARK));
        findViewById(R.id.themeAmoled).setOnClickListener(
                v -> chooseTheme(ThemeManager.AMOLED));

        markSelectedTheme();
    }

    private void chooseTheme(@ThemeManager.Mode String mode) {
        if (mode.equals(ThemeManager.current(this))) return;

        ThemeManager.apply(this, mode);

        recreate();
    }

    private void markSelectedTheme() {
        String mode = ThemeManager.current(this);

        style(tileLight, labelLight, ThemeManager.LIGHT.equals(mode));
        style(tileDark, labelDark, ThemeManager.DARK.equals(mode));
        style(tileAmoled, labelAmoled, ThemeManager.AMOLED.equals(mode));
    }

    private void style(MaterialCardView tile, TextView label, boolean selected) {
        tile.setStrokeWidth(dp(selected ? 3 : 1));
        tile.setStrokeColor(ContextCompat.getColor(
                this, selected ? R.color.primary : R.color.divider));
        label.setTextColor(ContextCompat.getColor(
                this, selected ? R.color.text_primary : R.color.text_secondary));
        label.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void updatePiConnectionSummary() {
        String ip = PiConnectionManager.getSavedIp(this);
        if (ip.isEmpty()) {
            tvPiConnectionSummary.setText(R.string.pi_not_connected);
            return;
        }

        long lastSync = PiSync.lastSyncMillis(this);
        tvPiConnectionSummary.setText(lastSync == 0
                ? getString(R.string.pi_connected_not_synced, ip)
                : getString(R.string.pi_connected_synced, ip,
                        new SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(new Date(lastSync))));
    }

    private void checkPiConnection() {
        String ip = PiConnectionManager.getSavedIp(this);
        if (ip.isEmpty()) {
            updatePiConnectionSummary();
            return;
        }

        tvPiConnectionSummary.setText(getString(R.string.pi_checking, ip));
        PiSync.checkHealth(this, (health, error) -> {
            if (isFinishing() || isDestroyed()) return;
            if (health != null) {
                updatePiConnectionSummary();
            } else {
                tvPiConnectionSummary.setText(getString(R.string.pi_unreachable, ip));
            }
        });
    }

    private void showPiIpDialog() {
        EditText input = new EditText(this);
        input.setHint("e.g.  192.168.1.42");
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setPadding(48, 32, 48, 32);

        String existing = PiConnectionManager.getSavedIp(this);
        if (!existing.isEmpty()) {
            input.setText(existing);
            input.setSelection(existing.length());
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.pi_ip_dialog_title)
                .setMessage(R.string.pi_ip_dialog_message_settings)
                .setView(input)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) {
                        PiConnectionManager.saveIp(this, ip);
                        checkPiConnection();
                    }
                })
                .setNeutralButton(R.string.action_clear, (dialog, which) -> {
                    PiConnectionManager.clearIp(this);
                    updatePiConnectionSummary();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }


    private void openContactSupport() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:"));
        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"support@pettracker.app"});
        intent.putExtra(Intent.EXTRA_SUBJECT, "Pet Tracker — Support Request");

        try {
            startActivity(Intent.createChooser(intent, "Contact Support"));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this,
                    getString(R.string.no_email_app_found_please_email),
                    Toast.LENGTH_LONG).show();
        }
    }
}