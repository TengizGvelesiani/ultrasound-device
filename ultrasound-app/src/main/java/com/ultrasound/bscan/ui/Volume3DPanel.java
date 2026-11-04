package com.ultrasound.bscan.ui;

import com.ultrasound.bscan.colormap.Colormap;
import com.ultrasound.bscan.colormap.ColormapRegistry;
import com.ultrasound.bscan.model.Render3DStyle;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.model.VolumeBuffer;
import com.ultrasound.bscan.model.VolumeScanConfig;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * High-performance 2.5D / 3D Volumetric Ultrasound Viewport.
 * Supports interactive orbit navigation, multi-angle camera presets,
 * depth-sorted shaded topography, wireframe mesh, C-scan depth gating,
 * and automated multi-view panoramic snapshot exports.
 */
public final class Volume3DPanel extends JPanel {

    public enum CameraPreset {
        ISOMETRIC("Perspective 3D", -45.0, 28.0, 1.0),
        TOP_DOWN("Top-Down (C-Scan)", 0.0, 88.0, 1.0),
        FRONT("Front Elevation (XZ)", 0.0, 4.0, 1.0),
        SIDE("Side Elevation (YZ)", 90.0, 4.0, 1.0),
        BACK_ISOMETRIC("Reverse Iso", 135.0, 28.0, 1.0);

        private final String label;
        private final double yaw;
        private final double pitch;
        private final double zoom;

        CameraPreset(String label, double yaw, double pitch, double zoom) {
            this.label = label;
            this.yaw = yaw;
            this.pitch = pitch;
            this.zoom = zoom;
        }

        public String getLabel() { return label; }
        public double getYaw() { return yaw; }
        public double getPitch() { return pitch; }
        public double getZoom() { return zoom; }

        @Override
        public String toString() { return label; }
    }

    public record MultiViewResult(
            BufferedImage compositeImage,
            BufferedImage isoImage,
            BufferedImage topImage,
            BufferedImage frontImage,
            BufferedImage sideImage,
            BufferedImage backIsoImage,
            BufferedImage activeImage
    ) {}

    private static final Color BG_COLOR = new Color(0x10, 0x10, 0x15);
    private static final Color BOX_COLOR = new Color(0x35, 0x3A, 0x4A);
    private static final Color AXIS_X_COLOR = new Color(0xFF, 0x55, 0x55);
    private static final Color AXIS_Y_COLOR = new Color(0x55, 0xFF, 0x77);
    private static final Color AXIS_Z_COLOR = new Color(0x55, 0xAA, 0xFF);
    private static final Color HEAD_COLOR = new Color(0xFF, 0xCC, 0x00);
    private static final Color HUD_BG = new Color(0x15, 0x1A, 0x25, 230);
    private static final Color HUD_BORDER = new Color(0x33, 0x44, 0x60);
    private static final Color HUD_TEXT = new Color(0xCC, 0xDD, 0xFF);

    private final ScanConfig scanConfig;
    private final VolumeBuffer volumeBuffer;
    private final ScannerState state;
    private final ColormapRegistry colormapRegistry;

    // Camera parameters
    private double yaw = -45.0;     // Azimuth in degrees
    private double pitch = 32.0;    // Elevation in degrees
    private double zoom = 1.0;
    private int panX = 0;
    private int panY = 0;

    // Mouse drag tracking
    private int lastMouseX;
    private int lastMouseY;

    private final Stroke boxStroke = new BasicStroke(1.2f);
    private final Stroke axisStroke = new BasicStroke(2.0f);
    private final Stroke gridStroke = new BasicStroke(0.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{3.0f, 3.0f}, 0.0f);

    public Volume3DPanel(ScanConfig scanConfig, VolumeBuffer volumeBuffer,
                         ScannerState state, ColormapRegistry colormapRegistry) {
        this.scanConfig = scanConfig;
        this.volumeBuffer = volumeBuffer;
        this.state = state;
        this.colormapRegistry = colormapRegistry;

        setPreferredSize(new Dimension(800, 500));
        setMinimumSize(new Dimension(400, 300));
        setOpaque(true);
        setBackground(BG_COLOR);

        setupCameraMouseListeners();
    }

    private void setupCameraMouseListeners() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastMouseX = e.getX();
                lastMouseY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lastMouseX;
                int dy = e.getY() - lastMouseY;

                if ((e.getModifiersEx() & MouseEvent.BUTTON3_DOWN_MASK) != 0 || e.isShiftDown()) {
                    // Right drag or Shift+drag: Pan
                    panX += dx;
                    panY += dy;
                } else {
                    // Left drag: Orbit Rotation
                    yaw += dx * 0.5;
                    pitch = Math.clamp(pitch + dy * 0.5, 5.0, 88.0);
                }

                lastMouseX = e.getX();
                lastMouseY = e.getY();
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                double factor = e.getPreciseWheelRotation() < 0 ? 1.12 : 0.89;
                zoom = Math.clamp(zoom * factor, 0.3, 5.0);
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    resetCamera();
                }
            }
        };

        addMouseListener(adapter);
        addMouseMotionListener(adapter);
        addMouseWheelListener(adapter);
    }

    public void setCameraPreset(CameraPreset preset) {
        this.yaw = preset.getYaw();
        this.pitch = preset.getPitch();
        this.zoom = preset.getZoom();
        this.panX = 0;
        this.panY = 0;
        repaint();
    }

    public void resetCamera() {
        setCameraPreset(CameraPreset.ISOMETRIC);
    }

    public double getYaw() { return yaw; }
    public double getPitch() { return pitch; }
    public double getZoom() { return zoom; }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        renderScene(g2, getWidth(), getHeight(),
                yaw, pitch, zoom, panX, panY,
                "2.5D / 3D Volumetric Ultrasound (" + state.getRender3DStyle().getLabel() + ")",
                true, true, true);
    }

    /**
     * Core renderer: draws the complete 3D scene at given dimensions and camera viewpoint.
     */
    public void renderScene(Graphics2D g2, int w, int h,
                            double viewYaw, double viewPitch, double viewZoom,
                            int panOffsetX, int panOffsetY,
                            String viewTitle, boolean showScanhead, boolean showHud, boolean showColorbar) {

        // Background
        g2.setColor(BG_COLOR);
        g2.fillRect(0, 0, w, h);

        VolumeScanConfig vConfig = state.getVolumeConfig();
        int gridX = volumeBuffer.getGridX();
        int gridY = volumeBuffer.getGridY();

        double totalWidthX = vConfig.getTotalWidthXMm();
        double totalHeightY = vConfig.getTotalHeightYMm();

        double gateMin = vConfig.getGateMinDepthMm();
        double gateMax = vConfig.getGateMaxDepthMm();
        if (gateMax <= gateMin) {
            gateMax = gateMin + 5.0;
        }

        // Dynamic depth framing: tightly frame the box to the measured specimen relief
        double minMeasuredZ = Double.MAX_VALUE;
        double maxMeasuredZ = -Double.MAX_VALUE;
        int measuredCount = 0;

        volumeBuffer.getLock().readLock().lock();
        try {
            float[] hMap = volumeBuffer.getHeightMapUnsafe();
            boolean[] mMap = volumeBuffer.getMeasuredMapUnsafe();
            int total = volumeBuffer.getGridX() * volumeBuffer.getGridY();
            for (int i = 0; i < total; i++) {
                if (mMap[i] && hMap[i] > 0.0f) {
                    float z = hMap[i];
                    if (z < minMeasuredZ) minMeasuredZ = z;
                    if (z > maxMeasuredZ) maxMeasuredZ = z;
                    measuredCount++;
                }
            }
        } finally {
            volumeBuffer.getLock().readLock().unlock();
        }

        double zBoxMin;
        double zBoxMax;
        if (measuredCount > 10 && maxMeasuredZ > minMeasuredZ) {
            double span = maxMeasuredZ - minMeasuredZ;
            double pad = Math.max(1.0, span * 0.15);
            zBoxMin = Math.max(0.0, minMeasuredZ - pad);
            zBoxMax = maxMeasuredZ + pad;
        } else {
            zBoxMin = Math.max(0.0, gateMin - 2.0);
            zBoxMax = gateMax + 2.0;
        }
        double zBoxSpan = Math.max(2.0, zBoxMax - zBoxMin);
        double zCenter = (zBoxMin + zBoxMax) * 0.5;
        double depthExaggeration = state.getDepthExaggeration();

        // 3D Projection Setup
        double radYaw = Math.toRadians(viewYaw);
        double radPitch = Math.toRadians(viewPitch);

        double cosY = Math.cos(radYaw);
        double sinY = Math.sin(radYaw);
        double cosP = Math.cos(radPitch);
        double sinP = Math.sin(radPitch);

        // Scale bounding box to fill ~85% of viewport so the object is large, crisp, and prominent
        double maxDimension = Math.max(Math.max(totalWidthX, totalHeightY), zBoxSpan * depthExaggeration);
        double baseScale = (Math.min(w, h) * 0.85 * viewZoom) / Math.max(10.0, maxDimension);
        int centerX = w / 2 + panOffsetX;
        int centerY = h / 2 + 15 + panOffsetY;

        // 1. Draw 3D Bounding Box & Axis Grid
        drawBoundingBox(g2, totalWidthX, totalHeightY, zBoxMin, zBoxMax, zCenter,
                cosY, sinY, cosP, sinP, baseScale, centerX, centerY, depthExaggeration);

        // 2. Render 2.5D / 3D Volumetric Surface
        Render3DStyle style = state.getRender3DStyle();
        if (style == Render3DStyle.SHADED_SURFACE) {
            renderShadedSurface(g2, gridX, gridY, vConfig, zBoxMin, zBoxMax, zCenter,
                    cosY, sinY, cosP, sinP, baseScale, centerX, centerY, depthExaggeration);
        } else if (style == Render3DStyle.WIREFRAME) {
            renderWireframe(g2, gridX, gridY, vConfig, zBoxMax, zCenter,
                    cosY, sinY, cosP, sinP, baseScale, centerX, centerY, depthExaggeration);
        } else {
            renderCScanPlane(g2, gridX, gridY, vConfig, zCenter,
                    cosY, sinY, cosP, sinP, baseScale, centerX, centerY);
        }

        // 3. Draw Scanhead Cursor Indicator
        if (showScanhead) {
            drawScanhead(g2, vConfig, zBoxMin, zBoxMax, zCenter,
                    cosY, sinY, cosP, sinP, baseScale, centerX, centerY, depthExaggeration);
        }

        // 4. Colorbar Legend
        if (showColorbar) {
            drawColorbar(g2, w - 120, h / 2 - 90, 16, 180, zBoxMin, zBoxMax);
        }

        // 5. Overlays & HUD
        if (showHud) {
            drawHud(g2, w, h, viewTitle, viewYaw, viewPitch, viewZoom);
        } else if (viewTitle != null && !viewTitle.isEmpty()) {
            drawTitleBadge(g2, viewTitle);
        }
    }

    private int[] project(double x, double y, double z,
                          double totalW, double totalH, double zCenter,
                          double cosY, double sinY, double cosP, double sinP,
                          double scale, int centerX, int centerY, double zExaggeration) {
        // Center around X and Y
        double xc = x - totalW * 0.5;
        double yc = y - totalH * 0.5;
        // z is depth from transducer. Features closer to transducer (smaller depth z, like the coin)
        // are elevated upward, so (zCenter - z) is positive for elevated features
        double zc = (zCenter - z) * zExaggeration;

        // Yaw rotation around vertical axis
        double x1 = xc * cosY - yc * sinY;
        double y1 = xc * sinY + yc * cosY;

        // Pitch rotation
        double y2 = y1 * cosP - zc * sinP;
        double z2 = y1 * sinP + zc * cosP;

        int sx = (int) Math.round(centerX + x1 * scale);
        int sy = (int) Math.round(centerY - z2 * scale);

        return new int[]{sx, sy, (int) Math.round(y2 * 100)}; // third element is depth for sorting
    }

    private void drawBoundingBox(Graphics2D g2, double tw, double th,
                                 double zBoxMin, double zBoxMax, double zCenter,
                                 double cosY, double sinY, double cosP, double sinP,
                                 double scale, int cx, int cy, double zExag) {
        int[] p000 = project(0,  0,  zBoxMin, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
        int[] p100 = project(tw, 0,  zBoxMin, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
        int[] p110 = project(tw, th, zBoxMin, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
        int[] p010 = project(0,  th, zBoxMin, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);

        int[] p001 = project(0,  0,  zBoxMax, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
        int[] p101 = project(tw, 0,  zBoxMax, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
        int[] p111 = project(tw, th, zBoxMax, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
        int[] p011 = project(0,  th, zBoxMax, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);

        // Floor Grid
        g2.setColor(new Color(0x14, 0x1A, 0x24));
        Polygon floorPoly = new Polygon(
                new int[]{p001[0], p101[0], p111[0], p011[0]},
                new int[]{p001[1], p101[1], p111[1], p011[1]}, 4);
        g2.fillPolygon(floorPoly);

        // Floor Grid Lines at 10mm intervals
        g2.setColor(new Color(0x22, 0x2C, 0x3E));
        g2.setStroke(new BasicStroke(0.8f));
        for (double gx = 10.0; gx < tw; gx += 10.0) {
            int[] gStart = project(gx, 0,  zBoxMax, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
            int[] gEnd   = project(gx, th, zBoxMax, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
            g2.drawLine(gStart[0], gStart[1], gEnd[0], gEnd[1]);
        }
        for (double gy = 10.0; gy < th; gy += 10.0) {
            int[] gStart = project(0,  gy, zBoxMax, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
            int[] gEnd   = project(tw, gy, zBoxMax, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
            g2.drawLine(gStart[0], gStart[1], gEnd[0], gEnd[1]);
        }

        // Box Edges
        g2.setColor(BOX_COLOR);
        g2.setStroke(boxStroke);

        // Bottom box
        g2.drawLine(p001[0], p001[1], p101[0], p101[1]);
        g2.drawLine(p101[0], p101[1], p111[0], p111[1]);
        g2.drawLine(p111[0], p111[1], p011[0], p011[1]);
        g2.drawLine(p011[0], p011[1], p001[0], p001[1]);

        // Top Box (Gate Min Plane)
        g2.setStroke(gridStroke);
        g2.drawLine(p000[0], p000[1], p100[0], p100[1]);
        g2.drawLine(p100[0], p100[1], p110[0], p110[1]);
        g2.drawLine(p110[0], p110[1], p010[0], p010[1]);
        g2.drawLine(p010[0], p010[1], p000[0], p000[1]);

        // Verticals
        g2.drawLine(p000[0], p000[1], p001[0], p001[1]);
        g2.drawLine(p100[0], p100[1], p101[0], p101[1]);
        g2.drawLine(p110[0], p110[1], p111[0], p111[1]);
        g2.drawLine(p010[0], p010[1], p011[0], p011[1]);

        // Primary Colored Axes from Floor Origin (0,0, zBoxMax)
        g2.setStroke(axisStroke);
        g2.setColor(AXIS_X_COLOR);
        g2.drawLine(p001[0], p001[1], p101[0], p101[1]);
        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        g2.drawString(String.format("X (%.0f mm)", tw), p101[0] + 6, p101[1]);

        g2.setColor(AXIS_Y_COLOR);
        g2.drawLine(p001[0], p001[1], p011[0], p011[1]);
        g2.drawString(String.format("Y (%.0f mm)", th), p011[0] - 25, p011[1] + 12);

        g2.setColor(AXIS_Z_COLOR);
        g2.drawLine(p001[0], p001[1], p000[0], p000[1]);
        g2.drawString(String.format("Z: %.0f-%.0f mm", zBoxMin, zBoxMax), p000[0] - 75, p000[1] - 8);
    }

    private static final class RenderQuad {
        final int[] px = new int[4];
        final int[] py = new int[4];
        final double camDepth;
        final Color fill;
        final Color edge;

        RenderQuad(int[] p00, int[] p10, int[] p11, int[] p01, Color fill, Color edge) {
            px[0] = p00[0]; px[1] = p10[0]; px[2] = p11[0]; px[3] = p01[0];
            py[0] = p00[1]; py[1] = p10[1]; py[2] = p11[1]; py[3] = p01[1];
            this.camDepth = (p00[2] + p10[2] + p11[2] + p01[2]) * 0.25;
            this.fill = fill;
            this.edge = edge;
        }
    }

    private void renderShadedSurface(Graphics2D g2, int gridX, int gridY, VolumeScanConfig vConfig,
                                     double zBoxMin, double zBoxMax, double zCenter,
                                     double cosY, double sinY, double cosP, double sinP,
                                     double scale, int cx, int cy, double zExag) {
        double tw = vConfig.getTotalWidthXMm();
        double th = vConfig.getTotalHeightYMm();
        double dx = vConfig.getStepSizeXMm();
        double dy = vConfig.getStepSizeYMm();

        Colormap colormap = colormapRegistry.get(state.getColormap());
        int[] lut = colormap.getLut();

        // Directional Light Vector from top-left: L = (0.5, -0.6, 1.2) normalized
        double lx = 0.5, ly = -0.6, lz = 1.2;
        double lLen = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= lLen; ly /= lLen; lz /= lLen;

        double zSpan = Math.max(1.0, zBoxMax - zBoxMin);
        List<RenderQuad> quadList = new ArrayList<>((gridX - 1) * (gridY - 1));

        volumeBuffer.getLock().readLock().lock();
        try {
            float[] heightMap = volumeBuffer.getHeightMapUnsafe();
            boolean[] measured = volumeBuffer.getMeasuredMapUnsafe();

            for (int y = 0; y < gridY - 1; y++) {
                for (int x = 0; x < gridX - 1; x++) {
                    int i00 = y * gridX + x;
                    int i10 = y * gridX + (x + 1);
                    int i11 = (y + 1) * gridX + (x + 1);
                    int i01 = (y + 1) * gridX + x;

                    int mCount = (measured[i00] ? 1 : 0) + (measured[i10] ? 1 : 0) +
                                 (measured[i11] ? 1 : 0) + (measured[i01] ? 1 : 0);

                    if (mCount == 0) continue; // skip wholly unmeasured areas

                    // Smooth interpolation fallback: avoid creating artificial cliffs for unmeasured corners
                    double mSum = (measured[i00] ? heightMap[i00] : 0) +
                                  (measured[i10] ? heightMap[i10] : 0) +
                                  (measured[i11] ? heightMap[i11] : 0) +
                                  (measured[i01] ? heightMap[i01] : 0);
                    double fallbackZ = mSum / mCount;

                    double z00 = measured[i00] ? heightMap[i00] : fallbackZ;
                    double z10 = measured[i10] ? heightMap[i10] : fallbackZ;
                    double z11 = measured[i11] ? heightMap[i11] : fallbackZ;
                    double z01 = measured[i01] ? heightMap[i01] : fallbackZ;

                    int[] p00 = project(x * dx,       y * dy,       z00, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
                    int[] p10 = project((x + 1) * dx, y * dy,       z10, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
                    int[] p11 = project((x + 1) * dx, (y + 1) * dy, z11, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
                    int[] p01 = project(x * dx,       (y + 1) * dy, z01, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);

                    // Surface Normal scaled by depth exaggeration (elevation h = zCenter - z)
                    double dzdx = -((z10 - z00 + z11 - z01) * 0.5 / dx) * zExag;
                    double dzdy = -((z01 - z00 + z11 - z10) * 0.5 / dy) * zExag;
                    double nx = -dzdx;
                    double ny = -dzdy;
                    double nz = 1.0;
                    double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    nx /= nLen; ny /= nLen; nz /= nLen;

                    // Hill-shading Lambertian diffuse + ambient
                    double diff = Math.max(0.0, nx * lx + ny * ly + nz * lz);
                    double shade = 0.40 + 0.60 * diff;

                    // Specimen Depth normalized colormap across full dynamic range
                    double avgZ = (z00 + z10 + z11 + z01) * 0.25;
                    double normZ = Math.clamp((avgZ - zBoxMin) / zSpan, 0.0, 1.0);
                    int colorIdx = (int) Math.round((1.0 - normZ) * 255.0);
                    int argb = lut[Math.clamp(colorIdx, 0, 255)];

                    int r = (int) Math.clamp(((argb >> 16) & 0xFF) * shade, 0, 255);
                    int g = (int) Math.clamp(((argb >> 8) & 0xFF) * shade, 0, 255);
                    int b = (int) Math.clamp((argb & 0xFF) * shade, 0, 255);

                    Color fill = new Color(r, g, b);
                    Color edge = new Color(Math.max(0, (int)(r * 0.85)), Math.max(0, (int)(g * 0.85)), Math.max(0, (int)(b * 0.85)), 120);

                    quadList.add(new RenderQuad(p00, p10, p11, p01, fill, edge));
                }
            }
        } finally {
            volumeBuffer.getLock().readLock().unlock();
        }

        // Painter's Algorithm: sort back-to-front by camera depth
        quadList.sort(Comparator.comparingDouble(q -> q.camDepth));

        for (RenderQuad q : quadList) {
            g2.setColor(q.fill);
            g2.fillPolygon(q.px, q.py, 4);
            g2.setColor(q.edge);
            g2.drawPolygon(q.px, q.py, 4);
        }
    }

    private void renderWireframe(Graphics2D g2, int gridX, int gridY, VolumeScanConfig vConfig, double zBoxMax, double zCenter,
                                 double cosY, double sinY, double cosP, double sinP,
                                 double scale, int cx, int cy, double zExag) {
        double tw = vConfig.getTotalWidthXMm();
        double th = vConfig.getTotalHeightYMm();
        double dx = vConfig.getStepSizeXMm();
        double dy = vConfig.getStepSizeYMm();

        g2.setColor(new Color(0x00, 0xEE, 0xCC));
        g2.setStroke(new BasicStroke(1.0f));

        volumeBuffer.getLock().readLock().lock();
        try {
            float[] heightMap = volumeBuffer.getHeightMapUnsafe();
            boolean[] measured = volumeBuffer.getMeasuredMapUnsafe();

            for (int y = 0; y < gridY; y++) {
                int[] px = new int[gridX];
                int[] py = new int[gridX];
                for (int x = 0; x < gridX; x++) {
                    int i = y * gridX + x;
                    double z = (measured[i] && heightMap[i] > 0) ? heightMap[i] : zBoxMax;
                    int[] p = project(x * dx, y * dy, z, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
                    px[x] = p[0];
                    py[x] = p[1];
                }
                g2.drawPolyline(px, py, gridX);
            }

            for (int x = 0; x < gridX; x++) {
                int[] px = new int[gridY];
                int[] py = new int[gridY];
                for (int y = 0; y < gridY; y++) {
                    int i = y * gridX + x;
                    double z = (measured[i] && heightMap[i] > 0) ? heightMap[i] : zBoxMax;
                    int[] p = project(x * dx, y * dy, z, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
                    px[y] = p[0];
                    py[y] = p[1];
                }
                g2.drawPolyline(px, py, gridY);
            }
        } finally {
            volumeBuffer.getLock().readLock().unlock();
        }
    }

    private void renderCScanPlane(Graphics2D g2, int gridX, int gridY, VolumeScanConfig vConfig, double zCenter,
                                  double cosY, double sinY, double cosP, double sinP,
                                  double scale, int cx, int cy) {
        double tw = vConfig.getTotalWidthXMm();
        double th = vConfig.getTotalHeightYMm();
        double dx = vConfig.getStepSizeXMm();
        double dy = vConfig.getStepSizeYMm();

        Colormap colormap = colormapRegistry.get(state.getColormap());
        int[] lut = colormap.getLut();

        volumeBuffer.getLock().readLock().lock();
        try {
            float[] cscan = volumeBuffer.getCScanMapUnsafe();
            boolean[] measured = volumeBuffer.getMeasuredMapUnsafe();

            for (int y = 0; y < gridY - 1; y++) {
                for (int x = 0; x < gridX - 1; x++) {
                    int i = y * gridX + x;
                    if (!measured[i]) continue;

                    int val = (int) cscan[i];
                    int argb = lut[Math.clamp(val, 0, 255)];

                    int[] p00 = project(x * dx,       y * dy,       zCenter, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, 1.0);
                    int[] p10 = project((x + 1) * dx, y * dy,       zCenter, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, 1.0);
                    int[] p11 = project((x + 1) * dx, (y + 1) * dy, zCenter, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, 1.0);
                    int[] p01 = project(x * dx,       (y + 1) * dy, zCenter, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, 1.0);

                    g2.setColor(new Color(argb));
                    Polygon quad = new Polygon(
                            new int[]{p00[0], p10[0], p11[0], p01[0]},
                            new int[]{p00[1], p10[1], p11[1], p01[1]}, 4);
                    g2.fillPolygon(quad);
                }
            }
        } finally {
            volumeBuffer.getLock().readLock().unlock();
        }
    }

    private void drawScanhead(Graphics2D g2, VolumeScanConfig vConfig, double zBoxMin, double zBoxMax, double zCenter,
                              double cosY, double sinY, double cosP, double sinP,
                              double scale, int cx, int cy, double zExag) {
        int curX = volumeBuffer.getCurrentX();
        int curY = volumeBuffer.getCurrentY();
        double hx = curX * vConfig.getStepSizeXMm();
        double hy = curY * vConfig.getStepSizeYMm();
        double tw = vConfig.getTotalWidthXMm();
        double th = vConfig.getTotalHeightYMm();

        int[] topHead = project(hx, hy, zBoxMin, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
        float curZ = volumeBuffer.getHeight(curX, curY);
        double beamTipZ = (curZ > 0) ? curZ : zBoxMax;
        int[] botHead = project(hx, hy, beamTipZ, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);

        g2.setColor(new Color(0xFF, 0xEE, 0x33, 180));
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{3.0f, 3.0f}, 0.0f));
        g2.drawLine(topHead[0], topHead[1], botHead[0], botHead[1]);

        g2.setColor(HEAD_COLOR);
        g2.fillOval(topHead[0] - 5, topHead[1] - 5, 10, 10);
        g2.setColor(Color.WHITE);
        g2.drawOval(topHead[0] - 6, topHead[1] - 6, 12, 12);

        g2.setColor(Color.RED);
        g2.fillOval(botHead[0] - 4, botHead[1] - 4, 8, 8);
    }

    private void drawColorbar(Graphics2D g2, int x, int y, int barW, int barH, double zMin, double zMax) {
        Colormap colormap = colormapRegistry.get(state.getColormap());
        int[] lut = colormap.getLut();

        // Draw color gradient
        for (int i = 0; i < barH; i++) {
            double frac = 1.0 - ((double) i / (barH - 1));
            int idx = Math.clamp((int) Math.round(frac * 255.0), 0, 255);
            g2.setColor(new Color(lut[idx]));
            g2.fillRect(x, y + i, barW, 1);
        }

        g2.setColor(new Color(0x66, 0x77, 0x99));
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRect(x, y, barW, barH);

        double zMid = (zMin + zMax) * 0.5;

        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        g2.setColor(Color.WHITE);
        g2.drawString(String.format("%.1f mm", zMin), x + barW + 6, y + 10);
        g2.setColor(new Color(0xAA, 0xBB, 0xDD));
        g2.drawString(String.format("%.1f mm", zMid), x + barW + 6, y + barH / 2 + 4);
        g2.drawString(String.format("%.1f mm", zMax), x + barW + 6, y + barH);

        g2.setColor(new Color(0x88, 0xCC, 0xFF));
        g2.drawString("Depth Z", x - 4, y - 8);
    }

    private void drawHud(Graphics2D g2, int w, int h, String title, double curYaw, double curPitch, double curZoom) {
        drawTitleBadge(g2, title);

        int totalPts = volumeBuffer.getGridX() * volumeBuffer.getGridY();
        int recPts = volumeBuffer.getLinesRecorded();
        double pct = (totalPts > 0) ? (recPts * 100.0 / totalPts) : 0.0;
        VolumeScanConfig vc = state.getVolumeConfig();

        String stats = String.format("Head: X:%d Y:%d (%.1f, %.1f mm) | Progress: %d/%d (%.0f%%)",
                volumeBuffer.getCurrentX(), volumeBuffer.getCurrentY(),
                volumeBuffer.getCurrentX() * vc.getStepSizeXMm(),
                volumeBuffer.getCurrentY() * vc.getStepSizeYMm(),
                recPts, totalPts, pct);

        g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
        int statsW = g2.getFontMetrics().stringWidth(stats);
        g2.setColor(HUD_BG);
        g2.fillRoundRect(w - statsW - 24, 12, statsW + 16, 26, 6, 6);
        g2.setColor(HUD_BORDER);
        g2.drawRoundRect(w - statsW - 24, 12, statsW + 16, 26, 6, 6);
        g2.setColor(HUD_TEXT);
        g2.drawString(stats, w - statsW - 16, 30);

        String hint = String.format("Orbit: Yaw %.0f° Pitch %.0f° Zoom %.1fx | [Left Drag]: Orbit | [Right Drag]: Pan | [Wheel]: Zoom | [Dbl Click]: Reset",
                curYaw, curPitch, curZoom);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g2.setColor(new Color(0x77, 0x88, 0xAA));
        g2.drawString(hint, 16, h - 10);
    }

    private void drawTitleBadge(Graphics2D g2, String title) {
        int fontSize = Math.max(12, g2.getDeviceConfiguration().getBounds().width > 1200 ? 15 : 12);
        g2.setFont(new Font("SansSerif", Font.BOLD, fontSize));
        int strW = g2.getFontMetrics().stringWidth(title);

        g2.setColor(HUD_BG);
        g2.fillRoundRect(12, 12, strW + 20, 28, 6, 6);
        g2.setColor(HUD_BORDER);
        g2.drawRoundRect(12, 12, strW + 20, 28, 6, 6);
        g2.setColor(Color.WHITE);
        g2.drawString(title, 22, 31);
    }

    /**
     * Renders an offscreen snapshot of the 3D volume at specific dimensions and camera angles.
     */
    public BufferedImage renderOffscreenView(int width, int height, double customYaw, double customPitch,
                                             double customZoom, String title, boolean showColorbar) {
        BufferedImage image = new BufferedImage(Math.max(400, width), Math.max(300, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        renderScene(g2, width, height, customYaw, customPitch, customZoom, 0, 0,
                title, false, false, showColorbar);

        g2.dispose();
        return image;
    }

    public BufferedImage renderOffscreenView(int width, int height, CameraPreset preset, String title, boolean showColorbar) {
        return renderOffscreenView(width, height, preset.getYaw(), preset.getPitch(), preset.getZoom(), title, showColorbar);
    }

    /**
     * Captures high-res active camera viewport.
     */
    public BufferedImage captureSnapshot() {
        int w = Math.max(getWidth(), 1920);
        int h = Math.max(getHeight(), 1200);
        return renderOffscreenView(w, h, yaw, pitch, zoom,
                "Active Viewport (" + String.format("%.0f° / %.0f°", yaw, pitch) + ")", true);
    }

    /**
     * Captures a comprehensive multi-angle suite from all sides + composite overview.
     */
    public MultiViewResult captureMultiViewSnapshots() {
        int singleW = 1920;
        int singleH = 1200;

        // Individual views with full title badges
        BufferedImage iso = renderOffscreenView(singleW, singleH, CameraPreset.ISOMETRIC,
                "3D ISOMETRIC TOPOGRAPHY (SURFACE RELIEF)", true);
        BufferedImage top = renderOffscreenView(singleW, singleH, CameraPreset.TOP_DOWN,
                "TOP-DOWN EN-FACE (C-SCAN XY MAP)", true);
        BufferedImage front = renderOffscreenView(singleW, singleH, CameraPreset.FRONT,
                "FRONT ELEVATION (XZ CROSS SECTION)", true);
        BufferedImage side = renderOffscreenView(singleW, singleH, CameraPreset.SIDE,
                "SIDE ELEVATION (YZ CROSS SECTION)", true);
        BufferedImage backIso = renderOffscreenView(singleW, singleH, CameraPreset.BACK_ISOMETRIC,
                "REVERSE ISOMETRIC (BACK VIEW)", true);
        BufferedImage active = renderOffscreenView(singleW, singleH, yaw, pitch, zoom,
                "USER INTERACTIVE VIEWPORT", true);

        // Sub-panels for master composite (rendered without individual badges to prevent duplicate text)
        BufferedImage compIso = renderOffscreenView(singleW, singleH, CameraPreset.ISOMETRIC, "", true);
        BufferedImage compTop = renderOffscreenView(singleW, singleH, CameraPreset.TOP_DOWN, "", true);
        BufferedImage compFront = renderOffscreenView(singleW, singleH, CameraPreset.FRONT, "", true);
        BufferedImage compSide = renderOffscreenView(singleW, singleH, CameraPreset.SIDE, "", true);

        BufferedImage composite = createMultiViewComposite(compIso, compTop, compFront, compSide);

        return new MultiViewResult(composite, iso, top, front, side, backIso, active);
    }

    /**
     * Creates a publication-grade 4-in-1 multi-view composite report (2048 x 1360).
     */
    private BufferedImage createMultiViewComposite(BufferedImage iso, BufferedImage top,
                                                   BufferedImage front, BufferedImage side) {
        int compW = 2048;
        int compH = 1360;
        BufferedImage comp = new BufferedImage(compW, compH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = comp.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Dark background
        g2.setColor(new Color(0x0C, 0x10, 0x18));
        g2.fillRect(0, 0, compW, compH);

        // Header Banner
        g2.setColor(new Color(0x16, 0x1E, 0x2E));
        g2.fillRect(0, 0, compW, 85);
        g2.setColor(new Color(0x28, 0x36, 0x50));
        g2.drawLine(0, 85, compW, 85);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 20));
        g2.drawString("ULTRASOUND 3D VOLUMETRIC ACQUISITION — MULTI-VIEW REPORT", 28, 38);

        VolumeScanConfig vc = state.getVolumeConfig();
        String meta = String.format("Acquired: %s  |  Raster Grid: %d x %d (%.1f x %.1f mm)  |  Depth Gate: %.1f - %.1f mm  |  Colormap: %s",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                volumeBuffer.getGridX(), volumeBuffer.getGridY(),
                vc.getTotalWidthXMm(), vc.getTotalHeightYMm(),
                vc.getGateMinDepthMm(), vc.getGateMaxDepthMm(),
                state.getColormap().getLabel());
        g2.setColor(new Color(0x88, 0xAA, 0xDD));
        g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g2.drawString(meta, 28, 66);

        // Sub-panel geometry: 2 x 2 layout
        int panelW = 980;
        int panelH = 590;
        int leftX = 28;
        int rightX = 1040;
        int topY = 100;
        int botY = 715;

        // Draw the 4 viewports
        drawCompositePanel(g2, top, leftX, topY, panelW, panelH, "[A] TOP-DOWN EN-FACE (C-SCAN XY MAP)");
        drawCompositePanel(g2, iso, rightX, topY, panelW, panelH, "[B] 3D ISOMETRIC TOPOGRAPHY (SHADED SURFACE)");
        drawCompositePanel(g2, front, leftX, botY, panelW, panelH, "[C] FRONT ELEVATION (XZ PROFILE)");
        drawCompositePanel(g2, side, rightX, botY, panelW, panelH, "[D] SIDE ELEVATION (YZ PROFILE)");

        // Bottom footer
        g2.setColor(new Color(0x66, 0x77, 0x99));
        g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g2.drawString("Generated by Ultrasound B-Scan 3D Tomography System (Java Edition)", 28, compH - 14);

        g2.dispose();
        return comp;
    }

    private void drawCompositePanel(Graphics2D g2, BufferedImage src, int x, int y, int w, int h, String badge) {
        g2.drawImage(src, x, y, w, h, null);

        // Border around panel
        g2.setColor(new Color(0x28, 0x36, 0x50));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRect(x, y, w, h);

        // Header Badge
        g2.setFont(new Font("SansSerif", Font.BOLD, 13));
        int strW = g2.getFontMetrics().stringWidth(badge);
        g2.setColor(new Color(0x10, 0x16, 0x24, 230));
        g2.fillRoundRect(x + 12, y + 12, strW + 20, 26, 6, 6);
        g2.setColor(new Color(0x35, 0x48, 0x6A));
        g2.drawRoundRect(x + 12, y + 12, strW + 20, 26, 6, 6);
        g2.setColor(Color.WHITE);
        g2.drawString(badge, x + 22, y + 30);
    }
}
