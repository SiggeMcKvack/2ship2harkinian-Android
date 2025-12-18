package com.dishii.mm;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.InputStream;

/**
 * ROM validation utility for instant feedback before copying ROM files.
 * Validates size, compression, byte order, and CRC32C checksum.
 */
public class RomValidator {

    // Valid ROM sizes in bytes
    public static final long SIZE_32MB = 32L * 1024 * 1024;
    public static final long SIZE_54MB = 54L * 1024 * 1024;
    public static final long SIZE_64MB = 64L * 1024 * 1024;

    // Known good CRC32C values (from Extract.cpp)
    public static final long CRC_MM_US_10 = 0x96F49400L;
    public static final long CRC_MM_GC = 0xBB434787L;

    // ROM format magic bytes (big-endian first 4 bytes)
    private static final int MAGIC_Z64 = 0x80371240;  // Big-endian (native N64)
    private static final int MAGIC_N64 = 0x40123780;  // Little-endian
    private static final int MAGIC_V64 = 0x37804012;  // Byte-swapped

    public enum ValidationResult {
        VALID,
        INVALID_SIZE,
        INVALID_CRC,
        COMPRESSED_FILE,
        READ_ERROR
    }

    public static class ValidationStatus {
        public ValidationResult result;
        public String message;
        public long fileSize;
        public long crc32c;
        public String romVersion;

        public boolean isValid() {
            return result == ValidationResult.VALID;
        }
    }

    /**
     * Validates a ROM file from a SAF Uri.
     * Performs size check, compression detection, byte order conversion, and CRC32C validation.
     *
     * @param resolver ContentResolver for accessing the file
     * @param uri      Uri of the selected ROM file
     * @return ValidationStatus with result and details
     */
    public static ValidationStatus validateRom(ContentResolver resolver, Uri uri) {
        ValidationStatus status = new ValidationStatus();

        try {
            // Get file size first (quick check)
            status.fileSize = getFileSize(resolver, uri);

            // Check size
            if (!isValidSize(status.fileSize)) {
                status.result = ValidationResult.INVALID_SIZE;
                status.message = String.format(
                        "Invalid ROM size: %.1f MB. Expected 32, 54, or 64 MB.",
                        status.fileSize / (1024.0 * 1024.0)
                );
                return status;
            }

            // Stream the ROM and calculate CRC without loading entire file into memory
            status.crc32c = calculateStreamingCrc(resolver, uri, status);
            if (status.result != null) {
                // Error occurred during streaming (compression detected or read error)
                return status;
            }

            // Check CRC against known good values
            if (status.crc32c == CRC_MM_US_10) {
                status.result = ValidationResult.VALID;
                status.romVersion = "MM US 1.0";
                status.message = "Valid ROM: Majora's Mask US 1.0";
            } else if (status.crc32c == CRC_MM_GC) {
                status.result = ValidationResult.VALID;
                status.romVersion = "MM GC";
                status.message = "Valid ROM: Majora's Mask GameCube";
            } else {
                status.result = ValidationResult.INVALID_CRC;
                status.message = String.format(
                        "Unknown ROM (CRC: 0x%08X). Supported: MM US 1.0, MM GameCube.",
                        status.crc32c
                );
            }

        } catch (Exception e) {
            status.result = ValidationResult.READ_ERROR;
            status.message = "Error reading ROM: " + e.getMessage();
        }

        return status;
    }

    private static long calculateStreamingCrc(ContentResolver resolver, Uri uri, ValidationStatus status) {
        final int BUFFER_SIZE = 65536;
        byte[] buffer = new byte[BUFFER_SIZE];
        Crc32cUtil.StreamingCrc32c crc = new Crc32cUtil.StreamingCrc32c();
        int romFormat = 0;

        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) {
                status.result = ValidationResult.READ_ERROR;
                status.message = "Failed to open ROM file.";
                return 0;
            }

            int bytesRead;
            boolean firstChunk = true;

            while ((bytesRead = is.read(buffer)) != -1) {
                if (firstChunk) {
                    firstChunk = false;
                    if (bytesRead < 16) {
                        status.result = ValidationResult.READ_ERROR;
                        status.message = "ROM file too small.";
                        return 0;
                    }
                    if (isCompressed(buffer)) {
                        status.result = ValidationResult.COMPRESSED_FILE;
                        status.message = "File appears to be compressed (ZIP/RAR/7z). Please extract it first.";
                        return 0;
                    }
                    romFormat = ((buffer[0] & 0xFF) << 24) |
                            ((buffer[1] & 0xFF) << 16) |
                            ((buffer[2] & 0xFF) << 8) |
                            (buffer[3] & 0xFF);
                }
                convertChunkToBigEndian(buffer, bytesRead, romFormat);
                crc.update(buffer, 0, bytesRead);
            }
            return crc.getValue();
        } catch (Exception e) {
            status.result = ValidationResult.READ_ERROR;
            status.message = "Error reading ROM: " + e.getMessage();
            return 0;
        }
    }

    private static void convertChunkToBigEndian(byte[] data, int length, int romFormat) {
        if (romFormat == MAGIC_Z64) {
            return;
        } else if (romFormat == MAGIC_N64) {
            int alignedLen = (length / 4) * 4;
            for (int i = 0; i < alignedLen; i += 4) {
                byte t0 = data[i];
                byte t1 = data[i + 1];
                data[i] = data[i + 3];
                data[i + 1] = data[i + 2];
                data[i + 2] = t1;
                data[i + 3] = t0;
            }
        } else if (romFormat == MAGIC_V64) {
            int alignedLen = (length / 2) * 2;
            for (int i = 0; i < alignedLen; i += 2) {
                byte t = data[i];
                data[i] = data[i + 1];
                data[i + 1] = t;
            }
        }
    }

    private static long getFileSize(ContentResolver resolver, Uri uri) {
        long size = -1;
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        }
        return size;
    }

    private static boolean isValidSize(long size) {
        return size == SIZE_32MB || size == SIZE_54MB || size == SIZE_64MB;
    }

    private static boolean isCompressed(byte[] data) {
        if (data.length < 6) return false;

        // ZIP: PK\x03\x04
        if (data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04) {
            return true;
        }
        // RAR: Rar!
        if (data[0] == 0x52 && data[1] == 0x61 && data[2] == 0x72 && data[3] == 0x21) {
            return true;
        }
        // 7z: 7z\xBC\xAF\x27\x1C
        if (data[0] == 0x37 && data[1] == 0x7A && (data[2] & 0xFF) == 0xBC &&
                (data[3] & 0xFF) == 0xAF && data[4] == 0x27 && data[5] == 0x1C) {
            return true;
        }
        return false;
    }

}
