package com.dishii.mm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher activity that conditionally shows ROM setup UI when mm.o2r is missing.
 * If mm.o2r exists, immediately launches the game (MainActivity).
 * Uses app's private external storage (Android/data/) - no special permissions needed.
 */
public class LauncherActivity extends AppCompatActivity {

    private static final String TAG = "LauncherActivity";

    private Button selectRomButton;
    private Button confirmButton;
    private TextView statusText;
    private TextView selectedFileText;
    private LinearLayout progressContainer;
    private ProgressBar progressBar;
    private TextView progressPercent;

    private SharedPreferences preferences;
    private ExecutorService executorService;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    // Validated ROM data
    private Uri validatedRomUri = null;
    private String validatedRomVersion = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences("com.dishii.mm.prefs", Context.MODE_PRIVATE);
        executorService = Executors.newSingleThreadExecutor();

        setupActivityLaunchers();

        // Check if O2R exists - if so, go straight to game
        if (checkO2rExists()) {
            launchGame();
            return;
        }

        // Show launcher UI for ROM setup
        setContentView(R.layout.activity_launcher);
        initializeViews();
    }

    private void setupActivityLaunchers() {
        // File picker launcher
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri selectedUri = result.getData().getData();
                        if (selectedUri != null) {
                            handleSelectedRom(selectedUri);
                        }
                    }
                }
        );

    }

    private void initializeViews() {
        selectRomButton = findViewById(R.id.selectRomButton);
        confirmButton = findViewById(R.id.confirmButton);
        statusText = findViewById(R.id.statusText);
        selectedFileText = findViewById(R.id.selectedFileText);
        progressContainer = findViewById(R.id.progressContainer);
        progressBar = findViewById(R.id.progressBar);
        progressPercent = findViewById(R.id.progressPercent);

        selectRomButton.setOnClickListener(v -> openFilePicker());
        confirmButton.setOnClickListener(v -> onConfirmClicked());

        // Initially hide confirm button until ROM is validated
        confirmButton.setVisibility(View.GONE);

        // Set version text
        TextView versionText = findViewById(R.id.versionText);
        versionText.setText("Version " + BuildConfig.VERSION_NAME);
    }

    private File getGameDataDir() {
        // Use app's private external storage - faster than SAF and no special permissions needed
        return getExternalFilesDir(null);
    }

    private boolean checkO2rExists() {
        File o2rFile = new File(getGameDataDir(), "mm.o2r");
        return o2rFile.exists() && o2rFile.length() > 0;
    }

    private void launchGame() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Filter to ROM file types
        String[] mimeTypes = {"application/octet-stream", "application/x-n64-rom", "*/*"};
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        filePickerLauncher.launch(intent);
    }

    private String getFileName(Uri uri) {
        String fileName = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
            }
        }
        if (fileName == null) {
            fileName = uri.getLastPathSegment();
        }
        return fileName;
    }

    private boolean isValidRomExtension(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".z64") || lower.endsWith(".n64") || lower.endsWith(".v64");
    }

    private void handleSelectedRom(Uri uri) {
        // Check file extension first
        String fileName = getFileName(uri);
        if (!isValidRomExtension(fileName)) {
            statusText.setText("Invalid file type. Please select a .z64, .n64, or .v64 file.");
            statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
            return;
        }

        // Show selected file name
        selectedFileText.setText(fileName);
        selectedFileText.setVisibility(View.VISIBLE);

        // Disable button during validation
        selectRomButton.setEnabled(false);
        confirmButton.setVisibility(View.GONE);
        statusText.setText("Validating ROM...");
        statusText.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));

        executorService.execute(() -> {
            // Validate ROM in background (reads full file for CRC)
            RomValidator.ValidationStatus validation = RomValidator.validateRom(getContentResolver(), uri);

            runOnUiThread(() -> {
                if (validation.isValid()) {
                    // Store validated ROM info
                    validatedRomUri = uri;
                    validatedRomVersion = validation.romVersion;

                    statusText.setText(validation.message);
                    statusText.setTextColor(getResources().getColor(R.color.success, getTheme()));

                    // Show confirm button instead of auto-proceeding
                    confirmButton.setVisibility(View.VISIBLE);
                    selectRomButton.setText("Select Different ROM");
                    selectRomButton.setEnabled(true);
                } else {
                    validatedRomUri = null;
                    validatedRomVersion = null;

                    statusText.setText(validation.message);
                    statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
                    selectRomButton.setEnabled(true);
                }
            });
        });
    }

    private void onConfirmClicked() {
        if (validatedRomUri == null) {
            statusText.setText("Please select a ROM file first.");
            statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
            return;
        }

        // Disable buttons during copy
        selectRomButton.setEnabled(false);
        confirmButton.setEnabled(false);

        copyRomAndExtract(validatedRomUri, validatedRomVersion);
    }

    private void copyRomAndExtract(Uri uri, String romVersion) {
        statusText.setText("Preparing files...");
        progressContainer.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressPercent.setText("");

        executorService.execute(() -> {
            try {
                // Use app's private external storage (Android/data/com.dishii.mm/files/)
                File targetRootFolder = getGameDataDir();
                if (!targetRootFolder.exists()) {
                    targetRootFolder.mkdirs();
                }

                // Copy assets if needed
                File assetsFolder = new File(targetRootFolder, "assets");
                if (!assetsFolder.exists() || assetsFolder.listFiles() == null || assetsFolder.listFiles().length == 0) {
                    runOnUiThread(() -> statusText.setText("Copying assets..."));
                    assetsFolder.mkdirs();
                    AssetCopyUtil.copyAssetsToExternal(this, "assets", assetsFolder.getAbsolutePath());
                }

                // Copy 2ship.o2r if bundled
                File shipOtrFile = new File(targetRootFolder, "2ship.o2r");
                if (!shipOtrFile.exists()) {
                    runOnUiThread(() -> statusText.setText("Copying game data..."));
                    copyBundledShipOtr(shipOtrFile);
                }

                // Copy ROM file
                runOnUiThread(() -> statusText.setText("Copying ROM file..."));
                File romFile = new File(targetRootFolder, "MM.z64");
                copyUriToFile(uri, romFile);

                // ROM copied - launch game which will handle extraction
                runOnUiThread(() -> {
                    statusText.setText("Launching game (extraction will run on first launch)...");
                    statusText.setTextColor(getResources().getColor(R.color.success, getTheme()));
                    progressContainer.setVisibility(View.GONE);
                    // Launch game after short delay
                    selectRomButton.postDelayed(this::launchGame, 500);
                });

            } catch (IOException e) {
                Log.e(TAG, "Error during setup", e);
                runOnUiThread(() -> {
                    statusText.setText("Error: " + e.getMessage());
                    statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
                    progressContainer.setVisibility(View.GONE);
                    selectRomButton.setEnabled(true);
                    confirmButton.setEnabled(true);
                });
            }
        });
    }

    private void copyUriToFile(Uri uri, File destFile) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(destFile)) {
            if (in == null) throw new IOException("Cannot open input stream");
            byte[] buffer = new byte[65536];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    private void copyBundledShipOtr(File destFile) {
        try {
            String[] assetList = getAssets().list("");
            boolean hasOtr = false;
            if (assetList != null) {
                for (String asset : assetList) {
                    if ("2ship.o2r".equals(asset)) {
                        hasOtr = true;
                        break;
                    }
                }
            }
            if (hasOtr) {
                try (InputStream in = getAssets().open("2ship.o2r");
                     OutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                Log.i(TAG, "Copied bundled 2ship.o2r");
            } else {
                Log.i(TAG, "2ship.o2r not bundled - will be created by extraction");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error copying 2ship.o2r", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
