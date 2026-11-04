package com.ultrasound.bscan.model;

import com.ultrasound.bscan.colormap.ColormapType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mutable presentation model managing user settings, view modes, and runtime metrics.
 */
public final class ScannerState {

    public static final double DEFAULT_CONTRAST = 1.2;
    public static final double DEFAULT_SQUELCH = 35.0;
    public static final double NEAR_FIELD_ZOOM_MAX_MM = 60.0;

    private ScanMode mode = ScanMode.SWEEP;
    private ViewMode viewMode = ViewMode.MODE_2D;
    private VolumeScanConfig volumeConfig = new VolumeScanConfig();
    private Render3DStyle render3DStyle = Render3DStyle.SHADED_SURFACE;
    private double depthExaggeration = 1.8;
    private boolean recording = true;
    private double contrast = DEFAULT_CONTRAST;
    private double squelch = DEFAULT_SQUELCH;
    private boolean zoomNear = false;
    private ColormapType colormap = ColormapType.BONE;

    private volatile AScanFrame latestFrame = null;
    private volatile long totalFramesReceived = 0;
    private volatile double currentFps = 0.0;

    private final List<StateChangeListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(StateChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(StateChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (StateChangeListener l : listeners) {
            l.onStateChanged(this);
        }
    }

    public ScanMode getMode() {
        return mode;
    }

    public void setMode(ScanMode mode) {
        if (this.mode != mode) {
            this.mode = mode;
            notifyListeners();
        }
    }

    public void toggleMode() {
        setMode(this.mode == ScanMode.SWEEP ? ScanMode.WATERFALL : ScanMode.SWEEP);
    }

    public ViewMode getViewMode() {
        return viewMode;
    }

    public void setViewMode(ViewMode viewMode) {
        if (this.viewMode != viewMode) {
            this.viewMode = viewMode;
            notifyListeners();
        }
    }

    public void toggleViewMode() {
        setViewMode(this.viewMode.toggle());
    }

    public VolumeScanConfig getVolumeConfig() {
        return volumeConfig;
    }

    public void setVolumeConfig(VolumeScanConfig volumeConfig) {
        this.volumeConfig = volumeConfig;
        notifyListeners();
    }

    public Render3DStyle getRender3DStyle() {
        return render3DStyle;
    }

    public void setRender3DStyle(Render3DStyle render3DStyle) {
        if (this.render3DStyle != render3DStyle) {
            this.render3DStyle = render3DStyle;
            notifyListeners();
        }
    }

    public void cycleRender3DStyle() {
        setRender3DStyle(this.render3DStyle.next());
    }

    public double getDepthExaggeration() {
        return depthExaggeration;
    }

    public void setDepthExaggeration(double depthExaggeration) {
        double clamped = Math.clamp(depthExaggeration, 0.5, 5.0);
        if (Math.abs(this.depthExaggeration - clamped) > 1e-4) {
            this.depthExaggeration = clamped;
            notifyListeners();
        }
    }

    public boolean isRecording() {
        return recording;
    }

    public void setRecording(boolean recording) {
        if (this.recording != recording) {
            this.recording = recording;
            notifyListeners();
        }
    }

    public void toggleRecording() {
        setRecording(!this.recording);
    }

    public double getContrast() {
        return contrast;
    }

    public void setContrast(double contrast) {
        double clamped = Math.clamp(contrast, 0.5, 3.0);
        if (Math.abs(this.contrast - clamped) > 1e-4) {
            this.contrast = clamped;
            notifyListeners();
        }
    }

    public double getSquelch() {
        return squelch;
    }

    public void setSquelch(double squelch) {
        double clamped = Math.clamp(squelch, 0.0, 150.0);
        if (Math.abs(this.squelch - clamped) > 1e-4) {
            this.squelch = clamped;
            notifyListeners();
        }
    }

    public boolean isZoomNear() {
        return zoomNear;
    }

    public void setZoomNear(boolean zoomNear) {
        if (this.zoomNear != zoomNear) {
            this.zoomNear = zoomNear;
            notifyListeners();
        }
    }

    public void toggleZoom() {
        setZoomNear(!this.zoomNear);
    }

    public ColormapType getColormap() {
        return colormap;
    }

    public void setColormap(ColormapType colormap) {
        if (this.colormap != colormap) {
            this.colormap = colormap;
            notifyListeners();
        }
    }

    public void cycleColormap() {
        setColormap(this.colormap.next());
    }

    public AScanFrame getLatestFrame() {
        return latestFrame;
    }

    public void setLatestFrame(AScanFrame latestFrame) {
        this.latestFrame = latestFrame;
    }

    public long getTotalFramesReceived() {
        return totalFramesReceived;
    }

    public void incrementFrames(long count) {
        this.totalFramesReceived += count;
    }

    public double getCurrentFps() {
        return currentFps;
    }

    public void setCurrentFps(double currentFps) {
        this.currentFps = currentFps;
    }
}
