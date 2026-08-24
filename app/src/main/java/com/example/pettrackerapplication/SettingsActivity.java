package com.example.pettrackerapplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsActivity extends AppCompatActivity {

    private static final boolean DEFAULT_SOUND_ENABLED = true;
    private static final int     DEFAULT_SOUND_VOLUME  = 70;

    private SharedPreferences prefs;

    private MaterialCardView cardAccountManagement;
    private MaterialCardView cardPiConnection;
    private MaterialCardView cardContactSupport;
    private TextView         tvPiConnectionSummary;
    private MaterialSwitch   swSound;
    private SeekBar          sbSoundVolume;

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

        // ── Account management — opens the Account screen ──────────────────
        cardAccountManagement.setOnClickListener(v ->
                startActivity(new Intent(this, AccountActivity.class)));

        // ── Pi Connection — shows saved IP, tap to edit via PiConnectionManager ──
        cardPiConnection.setOnClickListener(v -> showPiIpDialog());

        // ── Contact Support — opens an email composer pre-addressed to support ──
        cardContactSupport.setOnClickListener(v -> openContactSupport());

        // ── Sound toggle + volume — restore saved state ─────────────────────
        boolean soundEnabled = prefs.getBoolean(Prefs.KEY_SOUND_ENABLED, DEFAULT_SOUND_ENABLED);
        int     soundVolume  = prefs.getInt(Prefs.KEY_SOUND_VOLUME, DEFAULT_SOUND_VOLUME);

        swSound.setChecked(soundEnabled);
        sbSoundVolume.setProgress(soundVolume);
        sbSoundVolume.setEnabled(soundEnabled); // volume is meaningless while sound is off

        swSound.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(Prefs.KEY_SOUND_ENABLED, isChecked).apply();
            sbSoundVolume.setEnabled(isChecked);
        });

        sbSoundVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // No-op — saved only once the user lifts their finger, see below
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
        // Refresh in case the IP was changed from Petmenu since this screen was last shown
        updatePiConnectionSummary();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Pi Connection
    // ══════════════════════════════════════════════════════════════════════

    private void updatePiConnectionSummary() {
        String ip = PiConnectionManager.getSavedIp(this);
        tvPiConnectionSummary.setText(
                ip.isEmpty() ? "Not connected" : "Connected: " + ip);
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
                        updatePiConnectionSummary();
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