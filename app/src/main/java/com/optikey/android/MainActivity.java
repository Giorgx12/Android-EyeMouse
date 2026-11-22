package com.optikey.android;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 100, 60, 60);

        TextView info = new TextView(this);
        info.setText("1. Concedi il permesso Fotocamera\n2. Attiva 'OptiKey Gaze' in Accessibilità\n3. Guarda i 15 punti rossi per calibrare");
        info.setTextSize(16f);
        info.setPadding(0, 0, 0, 50);

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
        btnRecal.setText("3. Ricalibra (15 Punti)");
        btnRecal.setOnClickListener(v -> {
            if (GazeAccessibilityService.instance != null) {
                GazeAccessibilityService.instance.startCalibration();
            } else {
                Toast.makeText(this, "Attiva prima il servizio in Accessibilità!", Toast.LENGTH_SHORT).show();
            }
        });

        layout.addView(info);
        layout.addView(btnCam);
        layout.addView(btnAcc);
        layout.addView(btnRecal);
        setContentView(layout);
    }
}
