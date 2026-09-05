package com.detassist;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity - Minimal Setup UI
 * 
 * This activity serves as:
 * 1. Permission request handler (RECORD_AUDIO, READ_CONTACTS, POST_NOTIFICATIONS)
 * 2. Default assistant setup guide
 * 3. Status display for the voice assistant service
 * 
 * The app's primary operation happens in the background via:
 * - AudioListenerService (wake-word detection)
 * - DetAssistVoiceInteractionService (OS-level assistant)
 * 
 * This activity is intentionally minimal - it exists only for initial setup.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    // Required permissions
    private static final String[] REQUIRED_PERMISSIONS;
    
    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            REQUIRED_PERMISSIONS = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.POST_NOTIFICATIONS,
            };
        } else {
            REQUIRED_PERMISSIONS = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
            };
        }
    }

    private TextView mStatusText;
    private TextView mPermissionStatus;
    private TextView mAssistantStatus;
    private TextView mAppCountText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Build UI programmatically (no XML layout dependency)
        buildUI();
        
        // Check permissions and status
        updateStatusDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusDisplay();
    }

    /**
     * Build the setup UI programmatically.
     */
    private void buildUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF121212);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 64, 48, 64);
        
        // Title
        TextView title = new TextView(this);
        title.setText("DetAssist");
        title.setTextSize(28f);
        title.setTextColor(0xFF4285F4);
        layout.addView(title);
        
        // Subtitle
        TextView subtitle = new TextView(this);
        subtitle.setText("Deterministic Offline Voice Assistant");
        subtitle.setTextSize(14f);
        subtitle.setTextColor(0xFF888888);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT);
        subParams.bottomMargin = 48;
        layout.addView(subtitle, subParams);
        
        // Separator
        View separator = new View(this);
        separator.setBackgroundColor(0xFF333333);
        LinearLayout.LayoutParams sepParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2);
        sepParams.bottomMargin = 32;
        layout.addView(separator, sepParams);
        
        // Permission Status
        mPermissionStatus = new TextView(this);
        mPermissionStatus.setTextSize(16f);
        mPermissionStatus.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams permParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT);
        permParams.bottomMargin = 16;
        layout.addView(mPermissionStatus, permParams);
        
        // Assistant Status
        mAssistantStatus = new TextView(this);
        mAssistantStatus.setTextSize(16f);
        mAssistantStatus.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams asstParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT);
        asstParams.bottomMargin = 16;
        layout.addView(mAssistantStatus, asstParams);
        
        // App Count
        mAppCountText = new TextView(this);
        mAppCountText.setTextSize(16f);
        mAppCountText.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams appParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT);
        appParams.bottomMargin = 32;
        layout.addView(mAppCountText, appParams);
        
        // Overall Status
        mStatusText = new TextView(this);
        mStatusText.setTextSize(14f);
        mStatusText.setTextColor(0xFFAAAAAA);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.bottomMargin = 48;
        layout.addView(mStatusText, statusParams);
        
        // Permission Button
        Button permButton = new Button(this);
        permButton.setText("Grant Permissions");
        permButton.setBackgroundColor(0xFF4285F4);
        permButton.setTextColor(0xFFFFFFFF);
        permButton.setPadding(32, 16, 32, 16);
        permButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions();
            }
        });
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.bottomMargin = 16;
        layout.addView(permButton, btnParams);
        
        // Set Assistant Button
        Button assistantButton = new Button(this);
        assistantButton.setText("Set as Default Assistant");
        assistantButton.setBackgroundColor(0xFF34A853);
        assistantButton.setTextColor(0xFFFFFFFF);
        assistantButton.setPadding(32, 16, 32, 16);
        assistantButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAssistantSettings();
            }
        });
        LinearLayout.LayoutParams asstBtnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT);
        asstBtnParams.bottomMargin = 16;
        layout.addView(assistantButton, asstBtnParams);
        
        // Start Service Button
        Button startButton = new Button(this);
        startButton.setText("Start Listening");
        startButton.setBackgroundColor(0xFFEA4335);
        startButton.setTextColor(0xFFFFFFFF);
        startButton.setPadding(32, 16, 32, 16);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAssistant();
            }
        });
        layout.addView(startButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT));
        
        scrollView.addView(layout);
        setContentView(scrollView);
    }

    /**
     * Request all required permissions.
     */
    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }
        
        if (needed.isEmpty()) {
            Toast.makeText(this, "All permissions already granted", Toast.LENGTH_SHORT).show();
            return;
        }
        
        ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    /**
     * Open system settings to set this app as default assistant.
     */
    private void openAssistantSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            // Fallback
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                startActivity(intent);
            } catch (Exception ex) {
                Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Start the voice assistant service.
     */
    private void startAssistant() {
        // Check permissions first
        boolean allGranted = true;
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        
        if (!allGranted) {
            Toast.makeText(this, "Please grant all permissions first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Start AudioListenerService
        DetAssistApp app = DetAssistApp.getInstance();
        if (app != null) {
            app.startAudioListener();
            Toast.makeText(this, "Assistant is now listening", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Update the status display.
     */
    private void updateStatusDisplay() {
        // Check permissions
        StringBuilder permText = new StringBuilder("Permissions:\n");
        boolean allGranted = true;
        
        permText.append("  • RECORD_AUDIO: ");
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            permText.append("✓ Granted");
        } else {
            permText.append("✗ Not granted");
            allGranted = false;
        }
        
        permText.append("\n  • READ_CONTACTS: ");
        if (hasPermission(Manifest.permission.READ_CONTACTS)) {
            permText.append("✓ Granted");
        } else {
            permText.append("✗ Not granted");
            allGranted = false;
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permText.append("\n  • POST_NOTIFICATIONS: ");
            if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                permText.append("✓ Granted");
            } else {
                permText.append("✗ Not granted");
                allGranted = false;
            }
        }
        
        mPermissionStatus.setText(permText.toString());
        mPermissionStatus.setTextColor(allGranted ? 0xFF34A853 : 0xFFEA4335);
        
        // Check assistant status
        String assistantPkg = Settings.Secure.getString(getContentResolver(), "voice_interaction_service");
        boolean isDefaultAssistant = assistantPkg != null && assistantPkg.contains("com.detassist");
        
        String asstStatus = "Default Assistant: " + (isDefaultAssistant ? "✓ Yes" : "✗ No (tap button below)");
        mAssistantStatus.setText(asstStatus);
        mAssistantStatus.setTextColor(isDefaultAssistant ? 0xFF34A853 : 0xFFFbbc04);
        
        // App index status
        DetAssistApp app = DetAssistApp.getInstance();
        if (app != null && app.getAppIndex() != null) {
            int count = app.getAppIndex().getAppCount();
            mAppCountText.setText("Indexed Apps: " + count + (count > 0 ? " ✓" : " (building...)"));
        } else {
            mAppCountText.setText("Indexed Apps: Building...");
        }
        
        // Overall status
        String status = "Status: " + (allGranted && isDefaultAssistant ? 
            "Ready - Long-press home button to activate" : 
            "Setup required - See instructions above");
        mStatusText.setText(status);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updateStatusDisplay();
            
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
