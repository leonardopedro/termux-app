package com.termux.app.emthin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.emthin.pad.DocBridge;
import com.emthin.pad.GpuContract;
import com.emthin.pad.WaylandSurfaceView;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Document-authoritative shell container combining Typst-laid-out pages,
 * live Termux terminal figures, and Wayland client figures launched by shells.
 */
public final class EmthinWorkspaceView extends LinearLayout {
    private static final String LOG_TAG = "emthinTermux";
    private static final String LAYOUT_ID = "termux-workspace";
    private static final String TERMUX_PREFIX = "termux:";
    private static final float PAGE_WIDTH_PT = 595.28f;
    private static final float TEXT_SIZE_PT = 10.0f;

    private static final String INITIAL_DOCUMENT =
            "= emthin · Termux\n";

    public interface Actions {
        void onRequestNewTerminal();
        void onFocusTerminalSession(TerminalSession session);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService renderWorker = Executors.newSingleThreadExecutor();
    private final List<PageHost> pages = new ArrayList<>();
    private final Map<String, TerminalSession> sessionByHandle = new HashMap<>();
    private final Set<String> appendedAppIds = new HashSet<>();

    private Actions actions;
    private TextView statusText;
    private LinearLayout editorPanel;
    private EditText docEditor;
    private ScrollView pageScroll;
    private LinearLayout pageStack;

    private long docHandle;
    private long compositorHandle;
    private String activeTerminalHandle = "";
    private String pendingScrollToAppId = "";
    private int expectedRenderSeq;
    private boolean renderInFlight;
    private boolean renderDirty;
    private boolean destroyed;

    private final Runnable waylandPump = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            pollPendingWaylandToplevels();
            requestAllFigureFrames();
            updateStatusBanner();
            main.postDelayed(this, 40);
        }
    };

    public EmthinWorkspaceView(Context context) {
        this(context, null);
    }

    public EmthinWorkspaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF0A0F16);
        initUi(context);
        docHandle = DocBridge.create(INITIAL_DOCUMENT);
        compositorHandle = EmthinWaylandBridge.ensureStarted(context);
        syncDocEditorFromModel();
    }

    public void setActions(Actions actions) {
        this.actions = actions;
    }

    private void initUi(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF131B26);
        int pad = dp(6);
        bar.setPadding(pad, pad, pad, pad);

        statusText = new TextView(context);
        statusText.setTextColor(0xFFDCE6F4);
        statusText.setTextSize(12f);
        LayoutParams statusLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(statusText, statusLp);

        Button editBtn = makeBarButton(context, "Doc");
        editBtn.setOnClickListener(v -> toggleEditorPanel());
        bar.addView(editBtn);

        Button addTermBtn = makeBarButton(context, "+Term");
        addTermBtn.setOnClickListener(v -> {
            if (actions != null) actions.onRequestNewTerminal();
        });
        bar.addView(addTermBtn);

        Button gpuBtn = makeBarButton(context, "GPU/CPU");
        gpuBtn.setOnClickListener(v -> {
            boolean nextCpu = !DocBridge.gpuIsCpuFallback();
            DocBridge.gpuSetForceCpu(nextCpu);
            requestAllFigureFrames();
            updateStatusBanner();
        });
        bar.addView(gpuBtn);

        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        editorPanel = new LinearLayout(context);
        editorPanel.setOrientation(VERTICAL);
        editorPanel.setBackgroundColor(0xFF101722);
        editorPanel.setVisibility(GONE);
        editorPanel.setPadding(pad, pad, pad, pad);

        docEditor = new EditText(context);
        docEditor.setTextColor(0xFFEAF0F8);
        docEditor.setBackgroundColor(0xFF0B1018);
        docEditor.setTextSize(12f);
        docEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        docEditor.setMinLines(4);
        docEditor.setMaxLines(8);
        editorPanel.addView(docEditor, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        Button applyBtn = makeBarButton(context, "Apply Document");
        applyBtn.setOnClickListener(v -> applyEditorText());
        editorPanel.addView(applyBtn, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        addView(editorPanel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        pageScroll = new ScrollView(context);
        pageScroll.setFillViewport(true);
        pageScroll.getViewTreeObserver().addOnScrollChangedListener(this::clipAllPages);
        pageScroll.getViewTreeObserver().addOnGlobalLayoutListener(this::clipAllPages);

        pageStack = new LinearLayout(context);
        pageStack.setOrientation(VERTICAL);
        pageScroll.addView(pageStack, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(pageScroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private Button makeBarButton(Context context, String label) {
        Button btn = new Button(context);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(11.5f);
        btn.setTextColor(0xFFF3F7FC);
        btn.setBackgroundColor(0xFF213146);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(34));
        lp.leftMargin = dp(4);
        btn.setLayoutParams(lp);
        btn.setPadding(dp(8), 0, dp(8), 0);
        return btn;
    }

    private void toggleEditorPanel() {
        boolean show = editorPanel.getVisibility() != VISIBLE;
        if (show) syncDocEditorFromModel();
        editorPanel.setVisibility(show ? VISIBLE : GONE);
    }

    private void syncDocEditorFromModel() {
        if (docHandle == 0) return;
        try {
            JSONObject state = new JSONObject(DocBridge.state(docHandle));
            String text = state.optString("text", "");
            docEditor.setText(text);
        } catch (JSONException ignored) {
        }
    }

    private void applyEditorText() {
        if (docHandle == 0) return;
        String next = docEditor.getText().toString();
        DocBridge.sync(docHandle, next, next.length());
        scheduleLayoutRender();
    }

    public void attachAndResume() {
        compositorHandle = EmthinWaylandBridge.ensureStarted(getContext());
        main.removeCallbacks(waylandPump);
        main.post(waylandPump);
        scheduleLayoutRender();
    }

    public void pausePump() {
        main.removeCallbacks(waylandPump);
    }

    public void releaseResources() {
        destroyed = true;
        main.removeCallbacks(waylandPump);
        clearPages();
        renderWorker.shutdownNow();
        if (docHandle != 0) {
            DocBridge.free(docHandle);
            docHandle = 0;
        }
    }

    public void syncSessions(List<TerminalSession> sessions, TerminalSession activeSession) {
        sessionByHandle.clear();
        activeTerminalHandle = activeSession == null ? "" : activeSession.mHandle;
        if (sessions != null) {
            for (TerminalSession s : sessions) {
                if (s == null) continue;
                sessionByHandle.put(s.mHandle, s);
                ensureAppFigure(TERMUX_PREFIX + s.mHandle, "Termux Shell", 480, 120);
            }
        }
        rebindExistingTermuxViews();
        scheduleLayoutRender();
    }

    public void notifySessionScreenUpdated(TerminalSession session) {
        if (session == null) return;
        String appId = TERMUX_PREFIX + session.mHandle;
        for (PageHost host : pages) {
            for (EmthinTermuxFigureView tv : host.termuxViews) {
                if (tv != null && appId.equals(tv.getTag())) {
                    tv.renderNow();
                }
            }
        }
    }

    private boolean ensureAppFigure(String appId, String caption, int wPt, int hPt) {
        if (docHandle == 0 || appId == null || appId.isEmpty()) return false;
        if (appendedAppIds.contains(appId)) return false;
        try {
            JSONObject st = new JSONObject(DocBridge.state(docHandle));
            String currentText = st.optString("text", "");
            if (currentText.contains("\"" + appId + "\"")) {
                appendedAppIds.add(appId);
                return false;
            }
            DocBridge.appendAppFigure(docHandle, caption, wPt, hPt, appId);
            appendedAppIds.add(appId);
            pendingScrollToAppId = appId;
            if (editorPanel.getVisibility() == VISIBLE) syncDocEditorFromModel();
            return true;
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "ensureAppFigure failed: " + e.getMessage());
            return false;
        }
    }

    private void rebindExistingTermuxViews() {
        for (PageHost host : pages) {
            for (EmthinTermuxFigureView tv : host.termuxViews) {
                if (tv == null) continue;
                Object tag = tv.getTag();
                if (tag instanceof String) {
                    String appId = (String) tag;
                    if (appId.startsWith(TERMUX_PREFIX)) {
                        String handle = appId.substring(TERMUX_PREFIX.length());
                        tv.bindSession(sessionByHandle.get(handle));
                    }
                }
            }
        }
    }

    private void pollPendingWaylandToplevels() {
        if (compositorHandle == 0 || docHandle == 0) return;
        String raw = DocBridge.waylandTakePendingToplevels(compositorHandle);
        if (raw == null || raw.isEmpty()) return;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "[]".equals(trimmed)) return;
        try {
            JSONArray pending = trimmed.startsWith("[")
                    ? new JSONArray(trimmed)
                    : new JSONObject(trimmed).optJSONArray("pending");
            if (pending == null || pending.length() == 0) return;
            boolean changed = false;
            for (int i = 0; i < pending.length(); i++) {
                JSONObject tl = pending.optJSONObject(i);
                if (tl == null) continue;
                String appId = tl.optString("appId", "").trim();
                String title = tl.optString("title", "").trim();
                if (appId.isEmpty()) appId = title.isEmpty() ? "wayland-client" : title;
                if (appId.startsWith(TERMUX_PREFIX)) continue;
                int w = Math.max(240, Math.min(480, tl.optInt("width", 400)));
                int h = Math.max(100, Math.min(180, tl.optInt("height", 130)));
                String caption = title.isEmpty() ? ("Wayland: " + appId) : title;
                if (ensureAppFigure(appId, caption, w, h)) {
                    Logger.logInfo(LOG_TAG, "waylandAutoSlot appId=" + appId + " w=" + w + " h=" + h);
                    pendingScrollToAppId = appId;
                    changed = true;
                }
            }
            if (changed) {
                scheduleLayoutRender();
            }
        } catch (JSONException e) {
            Logger.logWarn(LOG_TAG, "pollPendingWaylandToplevels parse error: " + e.getMessage());
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && w != oldw) {
            scheduleLayoutRender();
        }
    }

    public void scheduleLayoutRender() {
        if (destroyed || docHandle == 0) return;
        if (renderInFlight) {
            renderDirty = true;
            return;
        }
        final int targetWidth = Math.max(360, pageScroll.getWidth() > 0 ? pageScroll.getWidth() : getWidth());
        if (targetWidth <= 0) {
            main.post(this::scheduleLayoutRender);
            return;
        }
        renderInFlight = true;
        renderDirty = false;
        final int renderSeq = ++expectedRenderSeq;
        final byte[] snapshot = DocBridge.snapshotBytes(docHandle);
        final String text;
        try {
            text = new JSONObject(DocBridge.state(docHandle)).optString("text", INITIAL_DOCUMENT);
        } catch (JSONException e) {
            renderInFlight = false;
            return;
        }

        renderWorker.execute(() -> {
            List<RenderedPageData> built = renderDocumentPages(snapshot, text, targetWidth);
            main.post(() -> {
                renderInFlight = false;
                if (destroyed) return;
                if (renderSeq == expectedRenderSeq && built != null) {
                    applyRenderedPages(built);
                }
                if (renderDirty) {
                    scheduleLayoutRender();
                }
            });
        });
    }

    private List<RenderedPageData> renderDocumentPages(byte[] snapshot, String text, int displayWidth) {
        List<RenderedPageData> result = new ArrayList<>();
        String preparedJson = DocBridge.prepareLayout(
                snapshot, text, LAYOUT_ID, 0, "[]", TEXT_SIZE_PT);
        try {
            JSONObject prepared = new JSONObject(preparedJson);
            if (!prepared.optString("error", "").isEmpty()) {
                Logger.logWarn(LOG_TAG, "prepareLayout error: " + prepared.optString("error"));
                return result;
            }
            int total = prepared.optInt("total", 0);
            JSONArray sizes = prepared.optJSONArray("pages");
            long pixelLimit = 4_000_000L;
            for (int i = 0; i < total; i++) {
                JSONObject ps = sizes == null ? null : sizes.optJSONObject(i);
                double widthPt = ps == null ? PAGE_WIDTH_PT : ps.optDouble("widthPt", PAGE_WIDTH_PT);
                double heightPt = ps == null ? 841.89 : ps.optDouble("heightPt", 841.89);
                float scale = (float) (displayWidth / Math.max(1.0, widthPt));
                String pageJson = DocBridge.renderLayoutPage(
                        LAYOUT_ID, text, i, scale, pixelLimit, 0, Math.max(0, total - 1));
                JSONObject p = new JSONObject(pageJson);
                if (!p.optString("error", "").isEmpty()) continue;
                byte[] png = Base64.decode(p.optString("png", ""), Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bmp == null) continue;
                result.add(new RenderedPageData(
                        p.optDouble("widthPt", widthPt),
                        p.optDouble("heightPt", heightPt),
                        p.optJSONArray("figures"),
                        bmp));
            }
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "renderDocumentPages JSON error: " + e.getMessage());
        }
        return result;
    }

    private void applyRenderedPages(List<RenderedPageData> nextPages) {
        clearPages();
        for (RenderedPageData data : nextPages) {
            PageHost host = new PageHost(getContext(), data);
            pages.add(host);
            LayoutParams lp = new LayoutParams(data.bitmap.getWidth(), data.bitmap.getHeight());
            lp.bottomMargin = dp(8);
            pageStack.addView(host, lp);
        }
        syncAllWaylandFigures();
        main.post(() -> {
            scrollToPendingFigure();
            clipAllPages();
            requestAllFigureFrames();
            updateStatusBanner();
        });
    }

    private void scrollToPendingFigure() {
        if (pendingScrollToAppId == null || pendingScrollToAppId.isEmpty() || pageScroll == null) return;
        String target = pendingScrollToAppId;
        pendingScrollToAppId = "";
        for (PageHost host : pages) {
            if (host.figures == null) continue;
            float scale = host.imageWidth() / (float) Math.max(1.0, host.widthPt);
            for (int i = 0; i < host.figures.length(); i++) {
                JSONObject fig = host.figures.optJSONObject(i);
                if (fig != null && target.equals(fig.optString("appId", ""))) {
                    int figTop = host.getTop() + Math.round((float) fig.optDouble("y", 0) * scale);
                    pageScroll.scrollTo(0, Math.max(0, figTop - dp(64)));
                    return;
                }
            }
        }
    }

    private void clearPages() {
        for (PageHost host : pages) {
            host.release();
        }
        pages.clear();
        pageStack.removeAllViews();
    }

    private void syncAllWaylandFigures() {
        if (compositorHandle == 0) return;
        JSONArray slots = new JSONArray();
        try {
            for (PageHost host : pages) {
                if (host.figures == null) continue;
                for (int i = 0; i < host.figures.length(); i++) {
                    JSONObject fig = host.figures.optJSONObject(i);
                    if (fig == null) continue;
                    String appId = fig.optString("appId", "");
                    String key = fig.optString("key", "");
                    if (key.isEmpty() || appId.isEmpty() || appId.startsWith(TERMUX_PREFIX)) continue;
                    JSONObject slot = new JSONObject();
                    slot.put("key", key);
                    slot.put("appId", appId);
                    slot.put("width", Math.max(1, (int) Math.round(fig.optDouble("w", 320.0))));
                    slot.put("height", Math.max(1, (int) Math.round(fig.optDouble("h", 200.0))));
                    slots.put(slot);
                }
            }
            DocBridge.waylandSyncFigures(compositorHandle, slots.toString());
        } catch (JSONException ignored) {
        }
    }

    private void clipAllPages() {
        for (PageHost host : pages) {
            host.clipFigureViews();
        }
    }

    private void requestAllFigureFrames() {
        for (PageHost host : pages) {
            host.requestFrames();
        }
    }

    private void updateStatusBanner() {
        String mode = DocBridge.gpuIsCpuFallback() ? "CPU-fallback" : "GPU-auto";
        int wlReady = 0;
        int termCount = 0;
        for (PageHost host : pages) {
            for (WaylandSurfaceView wv : host.waylandViews) {
                if (wv != null) {
                    GpuContract.RenderResult rr = wv.getLastResult();
                    if (rr != null && rr.rendered) wlReady++;
                }
            }
            for (EmthinTermuxFigureView tv : host.termuxViews) {
                if (tv != null) termCount++;
            }
        }
        statusText.setText("emthin · " + mode + " · " + pages.size() + "p · "
                + termCount + " term · " + wlReady + " wl");
    }

    /**
     * Automated in-process smoke verifier triggered when launching Termux with
     * {@code --ez com.termux.emthin.smoke true}.
     */
    public void runSmoke(final TermuxActivity activity) {
        new Thread(() -> {
            try {
                EmthinTermuxFigureView termView = waitForTermuxFigure(10_000);
                if (termView == null) {
                    Logger.logError(LOG_TAG, "smoke FAIL: timed out waiting for rendered Termux figure");
                    return;
                }
                GpuContract.RenderResult termRes = termView.getLastResult();
                Logger.logInfo(LOG_TAG, "smoke termuxFigure key=" + termView.getFigureKey()
                        + " backend=" + termRes.backend + " ok=" + termRes.rendered);

                final TerminalSession session = termView.getSession();
                if (session == null) {
                    Logger.logError(LOG_TAG, "smoke FAIL: missing bound TerminalSession");
                    return;
                }
                final String demoBin = EmthinWaylandBridge.getDemoBinaryPath(getContext());
                final String cmd = "export XDG_RUNTIME_DIR=" + EmthinWaylandBridge.getRuntimeDir(getContext()).getAbsolutePath()
                        + " WAYLAND_DISPLAY=" + EmthinWaylandBridge.WAYLAND_DISPLAY_NAME + "\n"
                        + "\"" + demoBin + "\" --app-id shell-wl-demo --title 'Shell Wayland' --seconds 15 &\n";
                main.post(() -> {
                    byte[] bytes = cmd.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    session.write(bytes, 0, bytes.length);
                });

                WaylandSurfaceView wlView = waitForWaylandFigure("shell-wl-demo", 12_000);
                if (wlView == null) {
                    Logger.logError(LOG_TAG, "smoke FAIL: timed out waiting for shell-wl-demo Wayland frame");
                    return;
                }
                GpuContract.RenderResult wlGpu = wlView.getLastResult();
                Logger.logInfo(LOG_TAG, "smoke waylandAutoSlot appId=shell-wl-demo key=" + wlView.getFigureKey());
                Logger.logInfo(LOG_TAG, "smoke waylandFrame key=" + wlView.getFigureKey()
                        + " backend=" + wlGpu.backend + " ok=" + wlGpu.rendered);

                DocBridge.gpuSetForceCpu(true);
                main.post(this::requestAllFigureFrames);
                GpuContract.RenderResult wlCpu = waitForCpuFallbackFrame(wlView, 5_000);
                DocBridge.gpuSetForceCpu(false);
                if (wlCpu == null || !wlCpu.rendered || !wlCpu.backend.startsWith("cpu")) {
                    Logger.logError(LOG_TAG, "smoke FAIL: CPU fallback frame did not complete (got="
                            + (wlCpu == null ? "null" : wlCpu.backend) + ")");
                    return;
                }
                Logger.logInfo(LOG_TAG, "smoke waylandCpuFrame key=" + wlView.getFigureKey()
                        + " backend=" + wlCpu.backend);
                Logger.logInfo(LOG_TAG, "smoke PASS");
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "smoke FAIL exception: " + t.getMessage());
            }
        }, "emthin-termux-smoke").start();
    }

    private EmthinTermuxFigureView waitForTermuxFigure(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !destroyed) {
            for (PageHost host : snapshotPages()) {
                for (EmthinTermuxFigureView tv : host.termuxViews) {
                    if (tv != null && tv.hasValidSurface()) {
                        GpuContract.RenderResult rr = tv.getLastResult();
                        if (rr != null && rr.rendered) return tv;
                    }
                }
            }
            main.post(this::requestAllFigureFrames);
            Thread.sleep(100);
        }
        return null;
    }

    private WaylandSurfaceView waitForWaylandFigure(String expectedAppId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !destroyed) {
            for (PageHost host : snapshotPages()) {
                for (int i = 0; i < host.waylandViews.length; i++) {
                    WaylandSurfaceView wv = host.waylandViews[i];
                    JSONObject fig = host.figures == null ? null : host.figures.optJSONObject(i);
                    if (wv == null || fig == null) continue;
                    if (!expectedAppId.equals(fig.optString("appId", ""))) continue;
                    GpuContract.RenderResult rr = wv.getLastResult();
                    if (wv.hasValidSurface() && rr != null && rr.rendered) {
                        return wv;
                    }
                }
            }
            main.post(() -> {
                pollPendingWaylandToplevels();
                clipAllPages();
                requestAllFigureFrames();
            });
            Thread.sleep(120);
        }
        return null;
    }

    private GpuContract.RenderResult waitForCpuFallbackFrame(WaylandSurfaceView view, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !destroyed) {
            main.post(view::requestFrame);
            GpuContract.RenderResult rr = view.getLastResult();
            if (rr != null && rr.rendered && rr.backend.startsWith("cpu")) {
                return rr;
            }
            Thread.sleep(80);
        }
        return view.getLastResult();
    }

    private List<PageHost> snapshotPages() {
        final List<PageHost> copy = new ArrayList<>();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        main.post(() -> {
            copy.addAll(pages);
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
        return copy;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class RenderedPageData {
        final double widthPt;
        final double heightPt;
        final JSONArray figures;
        final Bitmap bitmap;

        RenderedPageData(double widthPt, double heightPt, JSONArray figures, Bitmap bitmap) {
            this.widthPt = widthPt;
            this.heightPt = heightPt;
            this.figures = figures;
            this.bitmap = bitmap;
        }
    }

    private final class PageHost extends FrameLayout {
        final double widthPt;
        final JSONArray figures;
        final ImageView pageImage;
        final EmthinTermuxFigureView[] termuxViews;
        final WaylandSurfaceView[] waylandViews;

        PageHost(Context context, RenderedPageData data) {
            super(context);
            this.widthPt = data.widthPt;
            this.figures = data.figures == null ? new JSONArray() : data.figures;

            pageImage = new ImageView(context);
            pageImage.setScaleType(ImageView.ScaleType.FIT_XY);
            pageImage.setImageBitmap(data.bitmap);
            addView(pageImage, new FrameLayout.LayoutParams(
                    data.bitmap.getWidth(), data.bitmap.getHeight()));

            int n = this.figures.length();
            termuxViews = new EmthinTermuxFigureView[n];
            waylandViews = new WaylandSurfaceView[n];
            float scale = data.bitmap.getWidth() / (float) Math.max(1.0, widthPt);

            for (int i = 0; i < n; i++) {
                JSONObject fig = this.figures.optJSONObject(i);
                if (fig == null) continue;
                String key = fig.optString("key", "");
                String appId = fig.optString("appId", "");
                if (key.isEmpty() || appId.isEmpty()) continue;

                RectF rect = figureRect(fig, scale);
                if (rect == null) continue;
                Logger.logInfo(LOG_TAG, "PageHost figure key=" + key + " appId=" + appId
                        + " rect=" + Math.round(rect.left) + "," + Math.round(rect.top)
                        + " " + Math.round(rect.width()) + "x" + Math.round(rect.height()));

                if (appId.startsWith(TERMUX_PREFIX)) {
                    String handle = appId.substring(TERMUX_PREFIX.length());
                    final TerminalSession bound = sessionByHandle.get(handle);
                    EmthinTermuxFigureView tv = new EmthinTermuxFigureView(context, key);
                    tv.setTag(appId);
                    tv.setLogicalSize(Math.round(rect.width()), Math.round(rect.height()));
                    tv.bindSession(bound);
                    tv.setOnTouchListener((v, ev) -> {
                        if (ev.getActionMasked() == MotionEvent.ACTION_UP && actions != null) {
                            TerminalSession live = tv.getSession();
                            if (live != null) actions.onFocusTerminalSession(live);
                        }
                        return true;
                    });
                    termuxViews[i] = tv;
                    FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                            Math.max(64, Math.round(rect.width())),
                            Math.max(48, Math.round(rect.height())));
                    tlp.leftMargin = Math.round(rect.left);
                    tlp.topMargin = Math.round(rect.top);
                    tlp.gravity = Gravity.TOP | Gravity.LEFT;
                    addView(tv, tlp);
                } else if (compositorHandle != 0) {
                    WaylandSurfaceView wv = new WaylandSurfaceView(context, compositorHandle, key);
                    final String figKey = key;
                    wv.setOnTouchListener((v, ev) -> {
                        int act = ev.getActionMasked();
                        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE
                                || act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                            double nx = ev.getX() / Math.max(1f, v.getWidth());
                            double ny = ev.getY() / Math.max(1f, v.getHeight());
                            DocBridge.waylandSendTouch(compositorHandle, figKey, act, nx, ny);
                        }
                        return true;
                    });
                    waylandViews[i] = wv;
                    FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                            Math.max(64, Math.round(rect.width())),
                            Math.max(48, Math.round(rect.height())));
                    wlp.leftMargin = Math.round(rect.left);
                    wlp.topMargin = Math.round(rect.top);
                    wlp.gravity = Gravity.TOP | Gravity.LEFT;
                    addView(wv, wlp);
                }
            }
        }

        private RectF figureRect(JSONObject fig, float scale) {
            float x = (float) fig.optDouble("x", 0) * scale;
            float y = (float) fig.optDouble("y", 0) * scale;
            float w = (float) fig.optDouble("w", 0) * scale;
            float h = (float) fig.optDouble("h", 0) * scale;
            if (w <= 0 || h <= 0) return null;
            return new RectF(x, y, x + w, y + h);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                main.post(this::clipFigureViews);
            }
        }

        int imageWidth() {
            return getWidth() > 0 ? getWidth() : pageImage.getLayoutParams().width;
        }

        void clipFigureViews() {
            int hostW = imageWidth();
            int hostH = getHeight() > 0 ? getHeight() : pageImage.getLayoutParams().height;
            if (hostW <= 0 || hostH <= 0) return;
            float scale = hostW / (float) Math.max(1.0, widthPt);
            float viewLeft = 0f, viewTop = 0f, viewRight = hostW, viewBottom = hostH;
            if (pageScroll != null && pageScroll.getWidth() > 0 && pageScroll.getHeight() > 0) {
                int scrollY = pageScroll.getScrollY();
                int scrollX = pageScroll.getScrollX();
                int topInStack = getTop();
                int leftInStack = getLeft();
                viewLeft = Math.max(0f, scrollX - leftInStack);
                viewTop = Math.max(0f, scrollY - topInStack);
                viewRight = Math.min(hostW, (scrollX + pageScroll.getWidth()) - leftInStack);
                viewBottom = Math.min(hostH, (scrollY + pageScroll.getHeight()) - topInStack);
            }

            for (int i = 0; i < figures.length(); i++) {
                JSONObject fig = figures.optJSONObject(i);
                if (fig == null) continue;
                RectF rect = figureRect(fig, scale);
                if (rect == null) continue;
                RectF visible = new RectF(
                        Math.max(rect.left, viewLeft),
                        Math.max(rect.top, viewTop),
                        Math.min(rect.right, viewRight),
                        Math.min(rect.bottom, viewBottom));
                int vw = Math.round(visible.width());
                int vh = Math.round(visible.height());
                float u0 = (visible.left - rect.left) / Math.max(1f, rect.width());
                float v0 = (visible.top - rect.top) / Math.max(1f, rect.height());
                float u1 = (visible.right - rect.left) / Math.max(1f, rect.width());
                float v1 = (visible.bottom - rect.top) / Math.max(1f, rect.height());

                EmthinTermuxFigureView tv = termuxViews[i];
                if (tv != null) {
                    if (vw <= 0 || vh <= 0) {
                        tv.setVisibility(GONE);
                    } else {
                        tv.setVisibility(VISIBLE);
                        tv.setLogicalSize(Math.round(rect.width()), Math.round(rect.height()));
                        tv.setCrop(u0, v0, u1, v1);
                        positionChild(tv, Math.round(visible.left), Math.round(visible.top), vw, vh);
                    }
                }
                WaylandSurfaceView wv = waylandViews[i];
                if (wv != null) {
                    if (vw <= 0 || vh <= 0) {
                        wv.setVisibility(GONE);
                    } else {
                        wv.setVisibility(VISIBLE);
                        wv.setCrop(u0, v0, u1, v1);
                        positionChild(wv, Math.round(visible.left), Math.round(visible.top), vw, vh);
                        wv.requestFrame();
                    }
                }
            }
        }

        private void positionChild(View child, int left, int top, int width, int height) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(width, height);
            }
            if (lp.width != width || lp.height != height
                    || lp.leftMargin != left || lp.topMargin != top
                    || lp.gravity != (Gravity.TOP | Gravity.LEFT)) {
                lp.width = width;
                lp.height = height;
                lp.leftMargin = left;
                lp.topMargin = top;
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                child.setLayoutParams(lp);
            }
        }

        void requestFrames() {
            for (EmthinTermuxFigureView tv : termuxViews) {
                if (tv != null && tv.getVisibility() == VISIBLE) {
                    tv.renderNow();
                }
            }
            for (WaylandSurfaceView wv : waylandViews) {
                if (wv != null && wv.getVisibility() == VISIBLE) {
                    wv.requestFrame();
                }
            }
        }

        void release() {
            for (EmthinTermuxFigureView tv : termuxViews) {
                if (tv != null) tv.release();
            }
            for (WaylandSurfaceView wv : waylandViews) {
                if (wv != null) wv.release();
            }
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) parent.removeView(this);
        }
    }
}
