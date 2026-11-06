package com.ultrasound.bscan.model;

import com.ultrasound.bscan.colormap.ColormapType;

import java.util.List;
import java.util.Objects;
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
    private LineScanConfig lineScanConfig = new LineScanConfig();
    private Render3DStyle render3DStyle = Render3DStyle.SHADED_SURFACE;
    private double depthExaggeration = 1.0;
    private boolean recording = true;
    private double contrast = DEFAULT_CONTRAST;
    private double squelch = DEFAULT_SQUELCH;
    private boolean zoomNear = false;
    private ColormapType colormap = ColormapType.TURBO;
    private boolean colormapInverted = false;
    private double colormapGamma = 1.0;
    private double colorMinDepthMm = 85.0;
    private double colorMaxDepthMm = 130.0;
    private boolean colorRangeLockToGate = true;

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
        setViewMode(this.viewMode == ViewMode.MODE_2D ? ViewMode.MODE_3D : ViewMode.MODE_2D);
    }

    public LineScanConfig getLineScanConfig() {
        return lineScanConfig;
    }

    public void setLineScanConfig(LineScanConfig lineScanConfig) {
        this.lineScanConfig = Objects.requireNonNull(lineScanConfig, "lineScanConfig must not be null");
        notifyListeners();
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
        double clamped = Math.clamp(depthExaggeration, 0.5, 4.0);
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
        double clamped = Math.clamp(squelch, 0.0, 200.0);
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

    public boolean isColormapInverted() {
        return colormapInverted;
    }

    public void setColormapInverted(boolean colormapInverted) {
        if (this.colormapInverted != colormapInverted) {
            this.colormapInverted = colormapInverted;
            notifyListeners();
        }
    }

    public void toggleColormapInverted() {
        setColormapInverted(!this.colormapInverted);
    }

    public double getColormapGamma() {
        return colormapGamma;
    }

    public void setColormapGamma(double gamma) {
        double clamped = Math.clamp(gamma, 0.2, 3.0);
        if (Math.abs(this.colormapGamma - clamped) > 1e-4) {
            this.colormapGamma = clamped;
            notifyListeners();
        }
    }

    public double getColorMinDepthMm() {
        return colorRangeLockToGate ? volumeConfig.getGateMinDepthMm() : colorMinDepthMm;
    }

    public void setColorMinDepthMm(double minMm) {
        this.colorMinDepthMm = Math.max(0.0, minMm);
        if (this.colorMaxDepthMm <= this.colorMinDepthMm) {
            this.colorMaxDepthMm = this.colorMinDepthMm + 0.5;
        }
        notifyListeners();
    }

    public double getColorMaxDepthMm() {
        return colorRangeLockToGate ? volumeConfig.getGateMaxDepthMm() : colorMaxDepthMm;
    }

    public void setColorMaxDepthMm(double maxMm) {
        this.colorMaxDepthMm = Math.max(0.5, maxMm);
        if (this.colorMaxDepthMm <= this.colorMinDepthMm) {
            this.colorMinDepthMm = Math.max(0.0, this.colorMaxDepthMm - 0.5);
        }
        notifyListeners();
    }

    public boolean isColorRangeLockToGate() {
        return colorRangeLockToGate;
    }

    public void setColorRangeLockToGate(boolean lock) {
        if (this.colorRangeLockToGate != lock) {
            this.colorRangeLockToGate = lock;
            if (lock) {
                this.colorMinDepthMm = volumeConfig.getGateMinDepthMm();
                this.colorMaxDepthMm = volumeConfig.getGateMaxDepthMm();
            }
            notifyListeners();
        }
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
