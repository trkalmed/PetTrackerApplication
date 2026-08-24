package com.example.pettrackerapplication;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AccountActivity extends AppCompatActivity {


    private TextView       tvUserEmail;

    private TextView       tvMemberSince;
    private MaterialButton btnChangePassword;
    private MaterialButton btnLogout;

    private FirebaseAuth auth;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);

        findViewById(R.id.btnBack).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        auth = FirebaseAuth.getInstance();

        tvUserEmail       = findViewById(R.id.UserEmail);
        tvMemberSince     = findViewById(R.id.UserUid);
        btnChangePassword = findViewById(R.id.btnChangePassword);
        btnLogout         = findViewById(R.id.btnLogout);

        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            goToLoginAndFinish();
            return;
        }

        populateUserInfo(user);

        btnChangePassword.setOnClickListener(v -> sendPasswordResetEmail());
        btnLogout.setOnClickListener(v -> logout());
    }

    private void populateUserInfo(FirebaseUser user) {
        tvUserEmail.setText(user.getEmail() != null ? user.getEmail() : "No email");

        if (user.getMetadata() != null) {
            long   creationMs = user.getMetadata().getCreationTimestamp();
            String date       = new SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                    .format(new Date(creationMs));
            tvMemberSince.setText(date);
        } else {
            tvMemberSince.setText("—");
        }
    }

    private void sendPasswordResetEmail() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || user.getEmail() == null) {
            Toast.makeText(this,
                    "No email associated with this account",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String email = user.getEmail();


        setPasswordResetLoadingState(true);

        auth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    setPasswordResetLoadingState(false);

                    if (task.isSuccessful()) {
                        Toast.makeText(this,
                                "Reset email sent to " + email,
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this,
                                "Failed to send reset email",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }
    private void setPasswordResetLoadingState(boolean loading) {
        btnChangePassword.setEnabled(!loading);
        btnLogout.setEnabled(!loading);
        btnChangePassword.setText(loading
                ? "Sending…"
                : "Send password reset email");
    }

    private void logout() {
        auth.signOut();
        goToLoginAndFinish();
    }
    private void goToLoginAndFinish() {
        Intent intent = new Intent(AccountActivity.this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}