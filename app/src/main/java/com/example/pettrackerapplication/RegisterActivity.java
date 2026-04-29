package com.example.pettrackerapplication;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;

public class RegisterActivity extends AppCompatActivity {


    private TextInputLayout   inputEmailReg;
    private TextInputLayout   inputPasswordReg;
    private TextInputEditText etEmailReg;
    private TextInputEditText etPasswordReg;
    private MaterialButton    btnRegister;
    private FirebaseAuth      auth;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        auth             = FirebaseAuth.getInstance();
        inputEmailReg    = findViewById(R.id.inputEmailReg);
        inputPasswordReg = findViewById(R.id.inputPasswordReg);
        etEmailReg       = findViewById(R.id.etEmailReg);
        etPasswordReg    = findViewById(R.id.etPasswordReg);
        btnRegister      = findViewById(R.id.btnRegister);

        btnRegister.setOnClickListener(v -> registerUser());
    }



    private void registerUser() {
        clearErrors();

        String email    = etEmailReg.getText()    != null
                ? etEmailReg.getText().toString().trim()    : "";
        String password = etPasswordReg.getText() != null
                ? etPasswordReg.getText().toString().trim() : "";



        if (TextUtils.isEmpty(email)) {
            inputEmailReg.setError("Email is required");
            etEmailReg.requestFocus();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            inputEmailReg.setError("Enter a valid email address");
            etEmailReg.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(password)) {
            inputPasswordReg.setError("Password is required");
            etPasswordReg.requestFocus();
            return;
        }

        if (password.length() < 6) {
            inputPasswordReg.setError("At least 6 characters required");
            etPasswordReg.requestFocus();
            return;
        }

        setLoadingState(true);

        auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    setLoadingState(false);

                    if (task.isSuccessful()) {
                        Toast.makeText(this,
                                "Account created. You can log in now.",
                                Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        String message = task.getException() != null
                                ? task.getException().getMessage()
                                : "Unknown error";
                        Toast.makeText(this,
                                "Registration failed: " + message,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void clearErrors() {
        inputEmailReg.setError(null);
        inputPasswordReg.setError(null);
    }

    private void setLoadingState(boolean loading) {
        btnRegister.setEnabled(!loading);
        btnRegister.setText(loading ? "Creating account…" : "Sign up");
    }
}