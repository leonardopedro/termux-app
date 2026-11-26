package com.termux.app.emthin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.emthin.pad.GpuContract;
import com.emthin.pad.GpuRuntime;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalRenderer;

import java.nio.ByteBuffer;

/**
 * SurfaceView backing one Termux {@link TerminalSession} slotted as an emthin
 * {@code \app} figure.
 *
 * <p>Renders the live terminal grid through Termux's {@link TerminalRenderer}
 * into RGBA8 and presents each frame through {@link GpuRuntime#renderRgba} so it
 * uses EGL/GLES3 on GPU-capable devices and falls back transparently to CPU
 * {@code ANativeWindow_lock} when GPU is unavailable or forced off.
 */
public final class EmthinTermuxFigureView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String GPU_SCOPE = "termux";

    private final String figureKey;
    private final TerminalRenderer renderer;

    private TerminalSession session;
    private Bitmap backingBitmap;
    private byte[] rgbaBytes;
    private ByteBuffer rgbaBuffer;

    private int logicalWidthPx = 480;
    private int logicalHeightPx = 300;
    private int lastCols = -1;
    private int lastRows = -1;

    private float cropU0 = 0f;
    private float cropV0 = 0f;
    private float cropU1 = 1f;
    private float cropV1 = 1f;

    private boolean surfaceValid;
    private volatile boolean released;
    private volatile GpuContract.RenderResult lastResult;

    public EmthinTermuxFigureView(Context context, String figureKey) {
        super(context);
        this.figureKey = figureKey;
        float density = context.getResources().getDisplayMetrics().density;
        int fontSizePx = Math.max(18, Math.round(13f * density));
        this.renderer = new TerminalRenderer(fontSizePx, Typeface.MONOSPACE);
        getHolder().addCallback(this);
        setFocusable(true);
    }

    public String getFigureKey() {
        return figureKey;
    }

    public boolean hasValidSurface() {
        return surfaceValid && getHolder().getSurface() != null && getHolder().getSurface().isValid();
    }

    public GpuContract.RenderResult getLastResult() {
        return lastResult;
    }

    public TerminalSession getSession() {
        return session;
    }

    public void bindSession(TerminalSession nextSession) {
        if (session != nextSession) {
            session = nextSession;
            lastCols = -1;
            lastRows = -1;
        }
        renderNow();
    }

    public void setLogicalSize(int widthPx, int heightPx) {
        int safeW = Math.max(64, widthPx);
        int safeH = Math.max(48, heightPx);
        if (safeW != logicalWidthPx || safeH != logicalHeightPx) {
            logicalWidthPx = safeW;
            logicalHeightPx = safeH;
        }
    }

    public void setCrop(float u0, float v0, float u1, float v1) {
        cropU0 = clamp01(u0);
        cropV0 = clamp01(v0);
        cropU1 = clamp01(u1);
        cropV1 = clamp01(v1);
    }

    public void renderNow() {
        if (released || !surfaceValid) {
            return;
        }
        SurfaceHolder holder = getHolder();
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            return;
        }
        int w = Math.max(64, logicalWidthPx);
        int h = Math.max(48, logicalHeightPx);
        int cellW = Math.max(4, Math.round(renderer.getFontWidth()));
        int cellH = Math.max(8, renderer.getFontLineSpacing());
        int cols = Math.max(20, w / cellW);
        int rows = Math.max(6, h / cellH);

        TerminalEmulator emulator = session == null ? null : session.getEmulator();
        if (session != null && (emulator == null || emulator.mColumns != cols || emulator.mRows != rows
                || cols != lastCols || rows != lastRows)) {
            lastCols = cols;
            lastRows = rows;
            session.updateSize(cols, rows, cellW, cellH);
            emulator = session.getEmulator();
        }

        if (backingBitmap == null || backingBitmap.getWidth() != w || backingBitmap.getHeight() != h) {
            if (backingBitmap != null) backingBitmap.recycle();
            backingBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            rgbaBytes = new byte[w * h * 4];
            rgbaBuffer = ByteBuffer.wrap(rgbaBytes);
        }

        Canvas canvas = new Canvas(backingBitmap);
        canvas.drawColor(0xFF0E141B);
        if (emulator != null) {
            renderer.render(emulator, canvas, 0, -1, -1, -1, -1);
        }

        rgbaBuffer.clear();
        backingBitmap.copyPixelsToBuffer(rgbaBuffer);

        lastResult = GpuRuntime.get().renderRgba(
                GPU_SCOPE, figureKey, holder.getSurface(),
                rgbaBytes, w, h, w * 4,
                cropU0, cropV0, cropU1, cropV1);
    }

    public void release() {
        released = true;
        surfaceValid = false;
        GpuRuntime.get().releaseSurface(GPU_SCOPE, figureKey);
        if (backingBitmap != null) {
            backingBitmap.recycle();
            backingBitmap = null;
        }
        rgbaBytes = null;
        rgbaBuffer = null;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceValid = true;
        renderNow();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceValid = width > 0 && height > 0;
        renderNow();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceValid = false;
        GpuRuntime.get().releaseSurface(GPU_SCOPE, figureKey);
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
