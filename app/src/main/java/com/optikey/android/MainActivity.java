package com.optikey.android;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences sp = getSharedPreferences("optikey_cal", MODE_PRIVATE);
        int savedDwell = sp.getInt("dwell_ms", 2000);
        boolean savedSnap = sp.getBoolean("snap_enabled", true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 80, 60, 60);

        TextView info = new TextView(this);
        info.setText("OPTIKEY GAZE v2.0\n1. Concedi il permesso Fotocamera\n2. Attiva 'OptiKey Gaze' in Accessibilità\n3. Usa la barra a destra per Pausa, Indietro, Home e Ricalibra");
        info.setTextSize(15f);
        info.setPadding(0, 0, 0, 40);

        Button btnCam = new Button(this);
        btnCam.setText("1. Permesso Fotocamera");
        btnCam.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 100);
            } else {
                Toast.makeText(this, "Fotocamera già autorizzata!", Toast.LENGTH_SHORT).show();
            }
        });

        Button btnAcc = new Button(this);
        btnAcc.setText("2. Apri Impostazioni Accessibilità");
        btnAcc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button btnRecal = new Button(this);
        btnRecal.setText("3. Ricalibra Ora (15 Punti)");
        btnRecal.setOnClickListener(v -> {
            if (GazeAccessibilityService.instance != null) {
                GazeAccessibilityService.instance.startCalibration();
            } else {
                Toast.makeText(this, "Attiva prima il servizio in Accessibilità!", Toast.LENGTH_SHORT).show();
            }
        });

        TextView txtDwell = new TextView(this);
        txtDwell.setText(String.format("Tempo di fissazione (Click): %.1f secondi", savedDwell / 1000f));
        txtDwell.setTextSize(15f);
        txtDwell.setPadding(0, 50, 0, 10);

        SeekBar seekDwell = new SeekBar(this);
        seekDwell.setMax(27); // da 0.8s (800ms) a 3.5s (3500ms)
        seekDwell.setProgress((savedDwell - 800) / 100);
        seekDwell.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int ms = 800 + (progress * 100);
                txtDwell.setText(String.format("Tempo di fissazione (Click): %.1f secondi", ms / 1000f));
                sp.edit().putInt("dwell_ms", ms).apply();
                if (GazeAccessibilityService.instance != null) {
                    GazeAccessibilityService.instance.dwellDurationMs = ms;
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        Switch swSnap = new Switch(this);
        swSnap.setText("Calamita intelligente sui pulsanti (Snap)");
        swSnap.setTextSize(15f);
        swSnap.setChecked(savedSnap);
        swSnap.setPadding(0, 40, 0, 20);
        swSnap.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean("snap_enabled", isChecked).apply();
            if (GazeAccessibilityService.instance != null) {
                GazeAccessibilityService.instance.snapEnabled = isChecked;
            }
        });

        layout.addView(info);
        layout.addView(btnCam);
        layout.addView(btnAcc);
        layout.addView(btnRecal);
        layout.addView(txtDwell);
        layout.addView(seekDwell);
        layout.addView(swSnap);
        setContentView(layout);
    }
}
