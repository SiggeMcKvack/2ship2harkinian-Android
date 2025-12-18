package com.dishii.mm;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class AssetCopyUtil {

    public static void copyAssetsToExternal(Context context, String assetsFolderPath, String externalFolderPath) throws IOException {
        AssetManager assetManager = context.getAssets();
        String[] assetFiles = assetManager.list(assetsFolderPath);

        for (String assetFile : assetFiles) {
            String assetPath = assetsFolderPath + File.separator + assetFile;
            String externalPath = externalFolderPath + File.separator + assetFile;

            if (assetManager.list(assetPath).length > 0) {
                // It's a directory
                // Check if the directory exists in the external storage
                File externalDir = new File(externalPath);
                if (!externalDir.exists()) {
                    externalDir.mkdirs(); // Create the directory if it doesn't exist
                }

                // Recursively copy contents of the directory
                copyAssetsToExternal(context, assetPath, externalPath);
            } else {
                // It's a file
                File externalFile = new File(externalPath);
                if (!externalFile.exists()) {
                    try (InputStream inStream = assetManager.open(assetPath);
                         OutputStream outStream = new FileOutputStream(externalPath)) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = inStream.read(buffer)) != -1) {
                            outStream.write(buffer, 0, read);
                        }
                    }
                }
            }
        }
    }

    public static void copyDirectory(File sourceDir, File targetDir) throws IOException {
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        File[] files = sourceDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            File dest = new File(targetDir, file.getName());
            if (file.isDirectory()) {
                copyDirectory(file, dest);
            } else {
                copyFile(file, dest);
            }
        }
    }

    public static void copyFile(File source, File dest) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64*1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}