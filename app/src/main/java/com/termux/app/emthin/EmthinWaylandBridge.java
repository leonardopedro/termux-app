package com.termux.app.emthin;

import android.content.Context;
import android.os.Build;
import android.system.Os;

import com.emthin.pad.DocBridge;
import com.termux.shared.logger.Logger;

import java.io.File;

/**
 * Process-wide lifecycle owner for emthin's embedded Wayland compositor socket.
 *
 * <p>Started by {@link com.termux.app.TermuxService} so child processes launched
 * from any Termux shell inherit {@code XDG_RUNTIME_DIR} and
 * {@code WAYLAND_DISPLAY=wayland-0} pointing straight into emthin's compositor.
 */
public final class EmthinWaylandBridge {
    private static final String LOG_TAG = "EmthinWaylandBridge";
    public static final String WAYLAND_DISPLAY_NAME = "wayland-0";

    private static long sCompositorHandle;
    private static String sSocketPath = "";

    private EmthinWaylandBridge() {}

    public static synchronized File getRuntimeDir(Context context) {
        return new File(context.getFilesDir(), "xdg-runtime");
    }

    public static synchronized String getSocketPath(Context context) {
        return new File(getRuntimeDir(context), WAYLAND_DISPLAY_NAME).getAbsolutePath();
    }

    public static String getDemoBinaryPath(Context context) {
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        if (libDir == null || libDir.isEmpty()) return "";
        return new File(libDir, "libemthin_wl_demo.so").getAbsolutePath();
    }

    public static synchronized long ensureStarted(Context context) {
        if (sCompositorHandle != 0) {
            return sCompositorHandle;
        }
        File runtimeDir = getRuntimeDir(context);
        if (!runtimeDir.exists() && !runtimeDir.mkdirs()) {
            Logger.logError(LOG_TAG, "Failed to create XDG_RUNTIME_DIR: " + runtimeDir);
            return 0;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Os.chmod(runtimeDir.getAbsolutePath(), 0700);
            }
        } catch (Throwable ignored) {
            runtimeDir.setReadable(true, true);
            runtimeDir.setWritable(true, true);
            runtimeDir.setExecutable(true, true);
        }

        String socketPath = new File(runtimeDir, WAYLAND_DISPLAY_NAME).getAbsolutePath();
        long handle = DocBridge.waylandStart(socketPath);
        if (handle == 0) {
            Logger.logError(LOG_TAG, "DocBridge.waylandStart failed for " + socketPath);
            return 0;
        }
        sCompositorHandle = handle;
        sSocketPath = socketPath;
        Logger.logInfo(LOG_TAG, "Started emthin Wayland compositor at " + socketPath);
        return sCompositorHandle;
    }

    public static synchronized long getHandle() {
        return sCompositorHandle;
    }

    public static synchronized String getActiveSocketPath() {
        return sSocketPath;
    }

    public static synchronized void stop() {
        if (sCompositorHandle != 0) {
            DocBridge.waylandStop(sCompositorHandle);
            Logger.logInfo(LOG_TAG, "Stopped emthin Wayland compositor at " + sSocketPath);
            sCompositorHandle = 0;
            sSocketPath = "";
        }
    }
}
