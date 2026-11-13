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

    private static final Color BG_COLOR = new Color(0x0E, 0x0E, 0x13);
    private static final Color BOX_COLOR = new Color(0x38, 0x3E, 0x50);
    private static final Color AXIS_X_COLOR = new Color(0xFF, 0x52, 0x52);
    private static final Color AXIS_Y_COLOR = new Color(0x00, 0xE6, 0x76);
    private static final Color AXIS_Z_COLOR = new Color(0x00, 0xA8, 0xFF);
    private static final Color HEAD_COLOR = new Color(0xFF, 0xCC, 0x00);
    private static final Color HUD_BG = new Color(0x16, 0x1C, 0x28, 245);
    private static final Color HUD_BORDER = new Color(0x38, 0x48, 0x68);
    private static final Color HUD_TEXT = new Color(0xD0, 0xE0, 0xFF);

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
     * Main rendering function - draws the 3D scene with the current camera settings.
     */
    public void renderScene(Graphics2D g2, int w, int h,
                            double viewYaw, double viewPitch, double viewZoom,
                            int panOffsetX, int panOffsetY,
                            String viewTitle, boolean showScanhead, boolean showHud, boolean showColorbar) {

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

        // Figure out what depth range to show colors for
        double zBoxMin;
        double zBoxMax;
        if (state.isColorRangeLockToGate()) {
            zBoxMin = gateMin;
            zBoxMax = gateMax;
        } else {
            zBoxMin = state.getColorMinDepthMm();
            zBoxMax = state.getColorMaxDepthMm();
        }
        if (zBoxMax <= zBoxMin) {
            zBoxMax = zBoxMin + 1.0;
        }
        double zBoxSpan = Math.max(0.5, zBoxMax - zBoxMin);
        double zCenter = (zBoxMin + zBoxMax) * 0.5;
        double depthExaggeration = state.getDepthExaggeration();

        // Set up 3D projection math
        double radYaw = Math.toRadians(viewYaw);
        double radPitch = Math.toRadians(viewPitch);

        double cosY = Math.cos(radYaw);
        double sinY = Math.sin(radYaw);
        double cosP = Math.cos(radPitch);
        double sinP = Math.sin(radPitch);

        // Scale the scene to fill most of the viewport nicely
        double maxDimension = Math.max(Math.max(totalWidthX, totalHeightY), zBoxSpan * depthExaggeration);
        double baseScale = (Math.min(w, h) * 0.85 * viewZoom) / Math.max(10.0, maxDimension);
        int centerX = w / 2 + panOffsetX;
        int centerY = h / 2 + 15 + panOffsetY;

        // Draw everything in order: box, surface, scanhead, colorbar, HUD
        drawBoundingBox(g2, totalWidthX, totalHeightY, zBoxMin, zBoxMax, zCenter,
                cosY, sinY, cosP, sinP, baseScale, centerX, centerY, depthExaggeration);

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

        if (showScanhead) {
            drawScanhead(g2, vConfig, zBoxMin, zBoxMax, zCenter,
                    cosY, sinY, cosP, sinP, baseScale, centerX, centerY, depthExaggeration);
        }

        if (showColorbar) {
            drawColorbar(g2, w - 120, h / 2 - 90, 16, 180, zBoxMin, zBoxMax);
        }

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
        // Center the coordinates
        double xc = x - totalW * 0.5;
        double yc = y - totalH * 0.5;

        // Depth gets exaggerated to make features more visible
        // Closer to transducer (smaller z) = higher elevation
        double zc = (zCenter - z) * zExaggeration;

        // Rotate around vertical axis (yaw)
        double x1 = xc * cosY - yc * sinY;
        double y1 = xc * sinY + yc * cosY;

        // Tilt view up/down (pitch)
        double y2 = y1 * cosP - zc * sinP;
        double z2 = y1 * sinP + zc * cosP;

        int sx = (int) Math.round(centerX + x1 * scale);
        int sy = (int) Math.round(centerY - z2 * scale);

        return new int[]{sx, sy, (int) Math.round(y2 * 100)};
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

        // Light comes from upper-left, slightly in front, to highlight surface features
        double lx = 0.5, ly = -0.6, lz = 1.2;
        double lLen = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= lLen; ly /= lLen; lz /= lLen;

        double zSpan = Math.max(1.0, zBoxMax - zBoxMin);

        // Adaptive mesh decimation - for large grids, skip some quads to keep rendering fast
        int stepX = Math.max(1, (gridX + 119) / 120);
        int stepY = Math.max(1, (gridY + 119) / 120);
        int estQuads = ((gridX - 1 + stepX - 1) / stepX) * ((gridY - 1 + stepY - 1) / stepY);
        List<RenderQuad> quadList = new ArrayList<>(Math.max(16, estQuads));

        volumeBuffer.getLock().readLock().lock();
        try {
            float[] heightMap = volumeBuffer.getHeightMapUnsafe();
            boolean[] measured = volumeBuffer.getMeasuredMapUnsafe();

            for (int y = 0; y < gridY - 1; y += stepY) {
                int yNext = Math.min(y + stepY, gridY - 1);
                for (int x = 0; x < gridX - 1; x += stepX) {
                    int xNext = Math.min(x + stepX, gridX - 1);
                    int i00 = y * gridX + x;
                    int i10 = y * gridX + xNext;
                    int i11 = yNext * gridX + xNext;
                    int i01 = yNext * gridX + x;

                    int mCount = (measured[i00] ? 1 : 0) + (measured[i10] ? 1 : 0) +
                                 (measured[i11] ? 1 : 0) + (measured[i01] ? 1 : 0);

                    if (mCount == 0) continue;

                    // If some corners haven't been scanned yet, fill them in using nearby measured points
                    // This prevents jagged holes and keeps the surface smooth during scanning
                    double mSum = (measured[i00] ? heightMap[i00] : 0) +
                                  (measured[i10] ? heightMap[i10] : 0) +
                                  (measured[i11] ? heightMap[i11] : 0) +
                                  (measured[i01] ? heightMap[i01] : 0);
                    double fallbackZ = mSum / mCount;

                    double z00 = measured[i00] ? heightMap[i00] : fallbackZ;
                    double z10 = measured[i10] ? heightMap[i10] : fallbackZ;
                    double z11 = measured[i11] ? heightMap[i11] : fallbackZ;
                    double z01 = measured[i01] ? heightMap[i01] : fallbackZ;

                    int[] p00 = project(x * dx,     y * dy,     z00, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
                    int[] p10 = project(xNext * dx, y * dy,     z10, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
                    int[] p11 = project(xNext * dx, yNext * dy, z11, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
                    int[] p01 = project(x * dx,     yNext * dy, z01, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);

                    // Calculate surface normal by comparing neighboring heights
                    double spanX = (xNext - x) * dx;
                    double spanY = (yNext - y) * dy;
                    double dzdx = -((z10 - z00 + z11 - z01) * 0.5 / spanX) * zExag;
                    double dzdy = -((z01 - z00 + z11 - z10) * 0.5 / spanY) * zExag;

                    // Don't let super steep cliffs blow out the lighting
                    dzdx = Math.clamp(dzdx, -6.0, 6.0);
                    dzdy = Math.clamp(dzdy, -6.0, 6.0);

                    double nx = -dzdx;
                    double ny = -dzdy;
                    double nz = 1.0;
                    double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    nx /= nLen; ny /= nLen; nz /= nLen;

                    // Basic lighting model: some ambient light plus directional shading
                    double diff = Math.max(0.0, nx * lx + ny * ly + nz * lz);
                    double shade = 0.62 + 0.38 * diff;

                    // Add a little specular highlight on surfaces facing the light
                    double spec = Math.pow(Math.max(0.0, 2.0 * diff * nz - lz), 12.0);

                    // Map the average depth to a color - deeper = cooler colors, shallow = warmer
                    double avgZ = (z00 + z10 + z11 + z01) * 0.25;
                    double normZ = Math.clamp((avgZ - zBoxMin) / zSpan, 0.0, 1.0);
                    double elevation = 1.0 - normZ;

                    // Apply contrast and gamma to make features pop out more
                    double contrastGain = Math.max(0.2, state.getContrast());
                    double gamma = Math.max(0.2, state.getColormapGamma());
                    double effectiveExp = 1.0 / (Math.max(0.25, contrastGain) * gamma);
                    double enhancedElev = Math.pow(elevation, effectiveExp);

                    int rawColorIdx = (int) Math.round(enhancedElev * 255.0);
                    int colorIdx = state.isColormapInverted() ? (255 - rawColorIdx) : rawColorIdx;
                    int argb = lut[Math.clamp(colorIdx, 0, 255)];

                    // Make colors brighter when contrast is turned up
                    double brightFactor = 0.65 + 0.35 * contrastGain;

                    int baseR = (argb >> 16) & 0xFF;
                    int baseG = (argb >> 8) & 0xFF;
                    int baseB = argb & 0xFF;

                    int specLight = (int) Math.round(spec * 55.0 * Math.min(2.0, contrastGain));

                    int r = (int) Math.clamp(baseR * shade * brightFactor + specLight, 0, 255);
                    int g = (int) Math.clamp(baseG * shade * brightFactor + specLight, 0, 255);
                    int b = (int) Math.clamp(baseB * shade * brightFactor + specLight, 0, 255);

                    // Keep a minimum brightness so nothing disappears into total darkness
                    int minFloor = (int) Math.round(20.0 * Math.min(1.5, contrastGain));
                    r = Math.max(r, minFloor);
                    g = Math.max(g, minFloor);
                    b = Math.max(b, minFloor);

                    Color fill = new Color(r, g, b);
                    Color edge = new Color(Math.max(0, (int)(r * 0.85)), Math.max(0, (int)(g * 0.85)), Math.max(0, (int)(b * 0.85)), 120);

                    quadList.add(new RenderQuad(p00, p10, p11, p01, fill, edge));
                }
            }
        } finally {
            volumeBuffer.getLock().readLock().unlock();
        }

        // Sort quads from back to front so they draw in the right order
        quadList.sort(Comparator.comparingDouble(q -> q.camDepth));

        // Only draw mesh edges on small grids - looks messy on large ones
        boolean drawMeshEdges = (gridX <= 45 && gridY <= 45);
        for (RenderQuad q : quadList) {
            g2.setColor(q.fill);
            g2.fillPolygon(q.px, q.py, 4);
            if (drawMeshEdges) {
                g2.setColor(q.edge);
                g2.drawPolygon(q.px, q.py, 4);
            }
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

            int stepX = Math.max(1, (gridX + 119) / 120);
            int stepY = Math.max(1, (gridY + 119) / 120);
            for (int y = 0; y < gridY; y += stepY) {
                int[] px = new int[gridX];
                int[] py = new int[gridX];
                double lastValidZ = zBoxMax;
                for (int x = 0; x < gridX; x++) {
                    int i = y * gridX + x;
                    if (measured[i] && heightMap[i] > 0) {
                        lastValidZ = heightMap[i];
                    }
                    double z = lastValidZ;
                    int[] p = project(x * dx, y * dy, z, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, zExag);
                    px[x] = p[0];
                    py[x] = p[1];
                }
                g2.drawPolyline(px, py, gridX);
            }

            for (int x = 0; x < gridX; x += stepX) {
                int[] px = new int[gridY];
                int[] py = new int[gridY];
                double lastValidZ = zBoxMax;
                for (int y = 0; y < gridY; y++) {
                    int i = y * gridX + x;
                    if (measured[i] && heightMap[i] > 0) {
                        lastValidZ = heightMap[i];
                    }
                    double z = lastValidZ;
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

            int stepX = Math.max(1, (gridX + 119) / 120);
            int stepY = Math.max(1, (gridY + 119) / 120);
            for (int y = 0; y < gridY - 1; y += stepY) {
                int yNext = Math.min(y + stepY, gridY - 1);
                for (int x = 0; x < gridX - 1; x += stepX) {
                    int xNext = Math.min(x + stepX, gridX - 1);
                    int i = y * gridX + x;
                    if (!measured[i]) continue;

                    double contrastGain = Math.max(0.2, state.getContrast());
                    int rawVal = (int) Math.round(cscan[i] * contrastGain);
                    int val = state.isColormapInverted() ? (255 - rawVal) : rawVal;
                    int argb = lut[Math.clamp(val, 0, 255)];

                    double brightFactor = 0.70 + 0.30 * contrastGain;
                    int r = (int) Math.clamp(((argb >> 16) & 0xFF) * brightFactor, 0, 255);
                    int g = (int) Math.clamp(((argb >> 8) & 0xFF) * brightFactor, 0, 255);
                    int b = (int) Math.clamp((argb & 0xFF) * brightFactor, 0, 255);

                    int minFloor = (int) Math.round(15.0 * Math.min(1.5, contrastGain));
                    r = Math.max(r, minFloor);
                    g = Math.max(g, minFloor);
                    b = Math.max(b, minFloor);

                    int[] p00 = project(x * dx,     y * dy,     zCenter, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, 1.0);
                    int[] p10 = project(xNext * dx, y * dy,     zCenter, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, 1.0);
                    int[] p11 = project(xNext * dx, yNext * dy, zCenter, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, 1.0);
                    int[] p01 = project(x * dx,     yNext * dy, zCenter, tw, th, zCenter, cosY, sinY, cosP, sinP, scale, cx, cy, 1.0);

                    g2.setColor(new Color(r, g, b));
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

        boolean inverted = state.isColormapInverted();

        // Draw color gradient
        for (int i = 0; i < barH; i++) {
            double frac = 1.0 - ((double) i / (barH - 1));
            int rawIdx = Math.clamp((int) Math.round(frac * 255.0), 0, 255);
            int idx = inverted ? (255 - rawIdx) : rawIdx;
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
        String barTitle = state.isColormapInverted() ? "Depth [Inv]" : "Depth Z";
        g2.drawString(barTitle, x - 4, y - 8);
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

        String hint = String.format("Yaw %.0f° Pitch %.0f° Zoom %.1fx | [V]: Colormap (%s) | [I]: Invert (%s) | [K]: Tune Colors | [+/-]: Bright (%.1fx)",
                curYaw, curPitch, curZoom, state.getColormap().getLabel(),
                state.isColormapInverted() ? "ON" : "OFF", state.getContrast());
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

        public ColormapRegistry getColormapRegistry() {
        return colormapRegistry;
    }

    public void autoFitColorRangeToObject() {
        volumeBuffer.getLock().readLock().lock();
        try {
            float[] hMap = volumeBuffer.getHeightMapUnsafe();
            float[] aMap = volumeBuffer.getAmplitudeMapUnsafe();
            boolean[] mMap = volumeBuffer.getMeasuredMapUnsafe();
            int total = volumeBuffer.getGridX() * volumeBuffer.getGridY();

            List<Float> validDepths = new ArrayList<>();
            float squelch = (float) state.getSquelch();
            for (int i = 0; i < total; i++) {
                if (mMap[i] && aMap[i] > squelch && hMap[i] > 0.0f) {
                    validDepths.add(hMap[i]);
                }
            }

            if (validDepths.size() >= 10) {
                validDepths.sort(Float::compare);
                // 5th to 95th percentile ignores spurious noise and ringdown outliers
                int p5Idx = (int) (validDepths.size() * 0.05);
                int p95Idx = (int) (validDepths.size() * 0.95);
                double minZ = Math.max(0.0, validDepths.get(p5Idx) - 0.2);
                double maxZ = validDepths.get(p95Idx) + 0.2;
                if (maxZ > minZ + 0.5) {
                    state.setColorRangeLockToGate(false);
                    state.setColorMinDepthMm(Math.round(minZ * 10.0) / 10.0);
                    state.setColorMaxDepthMm(Math.round(maxZ * 10.0) / 10.0);
                    repaint();
                }
            }
        } finally {
            volumeBuffer.getLock().readLock().unlock();
        }
    }
}
