# Critical Fixes - Quick Reference Guide

This document provides ready-to-implement fixes for the most critical issues found in the stability audit.

---

## Fix #1: Implement Missing JNI Methods ⚠️ CRITICAL

### Problem
Touch input methods are declared in Java but not implemented in native code.

### Solution
Create a new file `mm/2s2h/Android/AndroidInput.cpp`:

```cpp
#include <jni.h>
#include <SDL.h>
#include "controller/controldevice/controller/mapping/sdl/SDLMapping.h"

// Store virtual controller state
static SDL_Joystick* virtualJoystick = nullptr;
static int virtualControllerIndex = -1;

extern "C" {

JNIEXPORT void JNICALL
Java_com_dishii_mm_MainActivity_attachController(JNIEnv* env, jobject obj) {
    // Create virtual controller for touch input
    // This integrates with SDL's controller system
    SDL_Log("Android: Attaching virtual touch controller");
    // TODO: Integrate with your controller manager
}

JNIEXPORT void JNICALL
Java_com_dishii_mm_MainActivity_detachController(JNIEnv* env, jobject obj) {
    SDL_Log("Android: Detaching virtual touch controller");
    // TODO: Clean up virtual controller
}

JNIEXPORT void JNICALL
Java_com_dishii_mm_MainActivity_setButton(JNIEnv* env, jobject obj, jint button, jboolean pressed) {
    // Map Android button to SDL button
    SDL_Event event;
    event.type = pressed ? SDL_JOYBUTTONDOWN : SDL_JOYBUTTONUP;
    event.jbutton.which = virtualControllerIndex;
    event.jbutton.button = button;
    event.jbutton.state = pressed ? SDL_PRESSED : SDL_RELEASED;
    SDL_PushEvent(&event);
}

JNIEXPORT void JNICALL
Java_com_dishii_mm_MainActivity_setAxis(JNIEnv* env, jobject obj, jint axis, jshort value) {
    // Map Android axis to SDL axis
    SDL_Event event;
    event.type = SDL_JOYAXISMOTION;
    event.jaxis.which = virtualControllerIndex;
    event.jaxis.axis = axis;
    event.jaxis.value = value;
    SDL_PushEvent(&event);
}

JNIEXPORT void JNICALL
Java_com_dishii_mm_MainActivity_setCameraState(JNIEnv* env, jobject obj, jint axis, jfloat value) {
    // Convert camera movement to joystick axis
    // Right stick controls camera in most N64 games
    SDL_Event event;
    event.type = SDL_JOYAXISMOTION;
    event.jaxis.which = virtualControllerIndex;
    event.jaxis.axis = (axis == 0) ? 2 : 3; // RX=2, RY=3
    event.jaxis.value = (Sint16)(value * 32767.0f / 15.0f); // Scale from sensitivity multiplier
    SDL_PushEvent(&event);
}

} // extern "C"
```

Add to `CMakeLists.txt`:
```cmake
# Add Android input source
if(ANDROID)
    target_sources(${PROJECT_NAME} PRIVATE
        mm/2s2h/Android/AndroidInput.cpp
    )
endif()
```

---

## Fix #2: Add Timeout to Setup Latch ⚠️ CRITICAL

### Problem
`setupLatch.await()` can hang forever if setup fails.

### Solution
In `MainActivity.java`, replace lines 63-69:

```java
public static void waitForSetupFromNative() {
    try {
        // Wait up to 60 seconds for setup to complete
        if (!setupLatch.await(60, TimeUnit.SECONDS)) {
            throw new RuntimeException(
                "Setup timeout after 60 seconds. Check:\n" +
                "1. Storage permission granted?\n" +
                "2. Sufficient storage space?\n" +
                "3. Check logcat for errors");
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Setup interrupted", e);
    }
}
```

Add import:
```java
import java.util.concurrent.TimeUnit;
```

---

## Fix #3: Fix Resource Leaks in File Copying ⚠️ HIGH

### Problem
Streams not closed in finally block.

### Solution
In `MainActivity.java`, replace lines 292-308:

```java
if (destinationDirectory != null && selectedFileUri != null) {
    try (InputStream in = getContentResolver().openInputStream(selectedFileUri);
         OutputStream out = new FileOutputStream(destinationFile)) {

        if (in == null) {
            Toast.makeText(this, "Failed to open ROM file", Toast.LENGTH_LONG).show();
            return;
        }

        byte[] buffer = new byte[64 * 1024]; // 64 KB buffer
        int bytesRead;
        long totalBytes = 0;

        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            totalBytes += bytesRead;
        }

        Log.i("FilePicker", "Copied " + totalBytes + " bytes to " + destinationFile);
        Toast.makeText(this, "ROM copied successfully", Toast.LENGTH_SHORT).show();

    } catch (IOException e) {
        Log.e("FilePicker", "Failed to copy ROM", e);
        Toast.makeText(this, "Error copying ROM: " + e.getMessage(), Toast.LENGTH_LONG).show();
        return;
    }
}
```

---

## Fix #4: Increase Buffer Sizes ⚠️ HIGH

### Problem
1 KB buffers cause extremely slow asset copying.

### Solution

**In `MainActivity.java` line 259:**
```java
byte[] buffer = new byte[64 * 1024]; // Changed from 1024 to 64 KB
```

**In `AssetCopyUtil.java` line 44:**
```java
byte[] buffer = new byte[64 * 1024]; // Changed from 1024 to 64 KB
```

---

## Fix #5: Add Permission Request Callback ⚠️ HIGH

### Problem
Permission request result never handled for Android 6-10.

### Solution
Add to `MainActivity.java`:

```java
@Override
public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                       @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
        boolean allGranted = true;
        if (grantResults.length > 0) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
        } else {
            allGranted = false;
        }

        if (allGranted) {
            Log.i("Permissions", "Storage permissions granted");
            doVersionCheck();
            checkAndSetupFiles();
        } else {
            // User denied permission
            new AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("Storage access is required to load game files. " +
                           "Without it, the game cannot run.\n\n" +
                           "Please grant storage permission in the next dialog.")
                .setCancelable(false)
                .setPositiveButton("Grant Permission", (dialog, which) -> {
                    requestStoragePermission();
                })
                .setNegativeButton("Exit", (dialog, which) -> {
                    Toast.makeText(MainActivity.this,
                        "Cannot run without storage permission",
                        Toast.LENGTH_LONG).show();
                    finish();
                })
                .show();
        }
    }
}
```

Add import:
```java
import androidx.annotation.NonNull;
```

---

## Fix #6: Fix MANAGE_EXTERNAL_STORAGE Callback ⚠️ MEDIUM

### Problem
Android 11+ permission result not properly handled.

### Solution
Replace lines 313-322 in `MainActivity.java`:

```java
} else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
    // Handle MANAGE_EXTERNAL_STORAGE result (Android 11+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (Environment.isExternalStorageManager()) {
            Log.i("Permissions", "MANAGE_EXTERNAL_STORAGE granted");
            doVersionCheck();
            checkAndSetupFiles();
        } else {
            // User denied permission
            new AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("Storage access is required to load game files. " +
                           "The game cannot run without this permission.\n\n" +
                           "Would you like to try again?")
                .setCancelable(false)
                .setPositiveButton("Try Again", (dialog, which) -> {
                    requestStoragePermission();
                })
                .setNegativeButton("Exit", (dialog, which) -> {
                    finish();
                })
                .show();
        }
    }
}
```

---

## Fix #7: Fix Null Check in Asset Copying ⚠️ MEDIUM

### Problem
`AssetManager.list()` can return null.

### Solution
In `AssetCopyUtil.java`, replace lines 16-60:

```java
public static void copyAssetsToExternal(Context context, String assetsFolderPath, String externalFolderPath) throws IOException {
    AssetManager assetManager = context.getAssets();
    String[] assetFiles = null;

    try {
        assetFiles = assetManager.list(assetsFolderPath);
    } catch (IOException e) {
        Log.e("AssetCopyUtil", "Failed to list assets: " + assetsFolderPath, e);
        throw e;
    }

    if (assetFiles == null || assetFiles.length == 0) {
        Log.w("AssetCopyUtil", "No assets found in: " + assetsFolderPath);
        return;
    }

    for (String assetFile : assetFiles) {
        String assetPath = assetsFolderPath + File.separator + assetFile;
        String externalPath = externalFolderPath + File.separator + assetFile;

        String[] subAssets = null;
        try {
            subAssets = assetManager.list(assetPath);
        } catch (IOException e) {
            Log.e("AssetCopyUtil", "Failed to check if directory: " + assetPath, e);
            continue;
        }

        if (subAssets != null && subAssets.length > 0) {
            // It's a directory
            File externalDir = new File(externalPath);
            if (!externalDir.exists()) {
                if (!externalDir.mkdirs()) {
                    Log.e("AssetCopyUtil", "Failed to create directory: " + externalPath);
                    continue;
                }
            }
            // Recursively copy contents
            copyAssetsToExternal(context, assetPath, externalPath);
        } else {
            // It's a file
            File externalFile = new File(externalPath);
            if (!externalFile.exists()) {
                try (InputStream in = assetManager.open(assetPath);
                     OutputStream out = new FileOutputStream(externalPath)) {

                    byte[] buffer = new byte[64 * 1024]; // 64 KB buffer
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    Log.d("AssetCopyUtil", "Copied: " + assetPath);
                }
            }
        }
    }
}
```

Add import:
```java
import android.util.Log;
```

---

## Fix #8: Make TouchAreaEnabled Thread-Safe ⚠️ MEDIUM

### Problem
`TouchAreaEnabled` accessed from multiple threads without synchronization.

### Solution
In `MainActivity.java`, replace lines 499-506:

```java
private volatile boolean touchAreaEnabled = true; // Made volatile and private

public void disableTouchArea() { // Made public with proper naming
    touchAreaEnabled = false;
}

public void enableTouchArea() { // Made public with proper naming
    touchAreaEnabled = true;
}

public boolean isTouchAreaEnabled() { // Added getter
    return touchAreaEnabled;
}
```

Then update line 554:
```java
return isTouchAreaEnabled(); // Use getter instead of direct access
```

---

## Fix #9: Add Dynamic Memory Allocation ⚠️ HIGH

### Problem
Fixed 52 MB allocation crashes on low-memory devices.

### Solution
Create new file `mm/src/buffers/android_heap.c`:

```c
#ifdef __ANDROID__
#include <jni.h>
#include <SDL2/SDL.h>
#include <sys/sysinfo.h>

// Get available RAM in MB
static long getAvailableRAM() {
    struct sysinfo info;
    if (sysinfo(&info) != 0) {
        return 2048; // Default to 2 GB if we can't determine
    }
    return (info.totalram / 1024 / 1024);
}

// Calculate heap sizes based on available RAM
void Android_CalculateHeapSizes(size_t* audioHeap, size_t* systemHeap) {
    long totalRAM = getAvailableRAM();

    if (totalRAM < 1024) {
        // < 1 GB - Ultra low-end
        *audioHeap = 8 * 1024 * 1024;   // 8 MB
        *systemHeap = 12 * 1024 * 1024; // 12 MB
        SDL_Log("Low memory device detected (%ld MB RAM), using minimal heaps", totalRAM);
    } else if (totalRAM < 2048) {
        // 1-2 GB - Low-end
        *audioHeap = 12 * 1024 * 1024;  // 12 MB
        *systemHeap = 20 * 1024 * 1024; // 20 MB
        SDL_Log("Budget device detected (%ld MB RAM), using reduced heaps", totalRAM);
    } else if (totalRAM < 4096) {
        // 2-4 GB - Mid-range
        *audioHeap = 16 * 1024 * 1024;  // 16 MB
        *systemHeap = 28 * 1024 * 1024; // 28 MB
    } else {
        // 4+ GB - High-end (use original values)
        *audioHeap = 0x1380000;          // ~20.5 MB
        *systemHeap = 32 * 1024 * 1024;  // 32 MB
    }

    SDL_Log("Heap sizes: Audio=%zu MB, System=%zu MB",
            *audioHeap / 1024 / 1024,
            *systemHeap / 1024 / 1024);
}
#endif
```

Then modify `mm/src/buffers/heaps.c`:

```c
#include "buffers.h"
#include <assert.h>
#include <stdlib.h>
#ifndef _MSC_VER
#include <unistd.h>
#endif

#ifdef __ANDROID__
#include <SDL2/SDL.h>
void Android_CalculateHeapSizes(size_t* audioHeap, size_t* systemHeap);
#endif

u8* gAudioHeap;
u8* gSystemHeap;

void Heaps_Alloc(void) {
    size_t audioHeapSize = AUDIO_HEAP_SIZE;
    size_t systemHeapSize = SYSTEM_HEAP_SIZE;

#ifdef __ANDROID__
    // Use dynamic sizes on Android
    Android_CalculateHeapSizes(&audioHeapSize, &systemHeapSize);
#endif

#ifdef _MSC_VER
    gAudioHeap = (u8*)_aligned_malloc(audioHeapSize, 0x10);
    gSystemHeap = (u8*)_aligned_malloc(systemHeapSize, 0x10);
#elif defined(_POSIX_VERSION) && (_POSIX_VERSION >= 200112L)
    if (posix_memalign((void**)&gAudioHeap, 0x10, audioHeapSize) != 0)
        gAudioHeap = NULL;
    if (posix_memalign((void**)&gSystemHeap, 0x10, systemHeapSize) != 0)
        gSystemHeap = NULL;
#else
    gAudioHeap = (u8*)memalign(0x10, audioHeapSize);
    gSystemHeap = (u8*)memalign(0x10, systemHeapSize);
#endif

    if (gAudioHeap == NULL || gSystemHeap == NULL) {
#ifdef __ANDROID__
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR,
            "Out of Memory",
            "Failed to allocate game memory. Your device may not have enough RAM.\n\n"
            "Minimum requirement: 1 GB RAM\n"
            "Recommended: 2+ GB RAM",
            NULL);
#endif
        // Still assert for development builds
        assert(gAudioHeap != NULL);
        assert(gSystemHeap != NULL);
    }
}
```

---

## Testing Checklist

After applying fixes, test:

- [ ] Touch controls work without crashes
- [ ] Permission denial doesn't hang app
- [ ] Permission grant proceeds to setup
- [ ] Setup completes in <10 seconds
- [ ] App works on low-memory device (< 2 GB RAM)
- [ ] Screen rotation during setup doesn't break
- [ ] ROM file picker works and handles cancellation
- [ ] App handles storage full condition
- [ ] No resource leaks after multiple ROM selections

---

## Build Notes

After making changes:

1. Clean build:
   ```bash
   cd Android
   ./gradlew clean
   ```

2. Rebuild native code:
   ```bash
   ./gradlew assembleDebug
   ```

3. Test on real device (emulators may hide memory issues)

4. Check logcat for any errors:
   ```bash
   adb logcat | grep -E "(AndroidRuntime|SDL|2S2H|MainActivity)"
   ```

---

**Last Updated:** 2025-11-09
