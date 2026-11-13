package com.ultrasound.bscan.ui;

import com.ultrasound.bscan.colormap.Colormap;
import com.ultrasound.bscan.colormap.ColormapRegistry;
import com.ultrasound.bscan.model.ScanBuffer;
import com.ultrasound.bscan.model.ScanConfig;
import com.ultrasound.bscan.model.ScanMode;
import com.ultrasound.bscan.model.ScannerState;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Ultra-fast hardware-accelerated 2D B-Scan cross-sectional ultrasound renderer.
 */
public final class BScanPanel extends JPanel {

    private static final Color BG_COLOR = new Color(0x10, 0x10, 0x14);
    private static final Color BORDER_COLOR = new Color(0x40, 0x44, 0x58);
    private static final Color GRID_COLOR = new Color(0x24, 0x26, 0x30);
    private static final Color TEXT_COLOR = new Color(0xC0, 0xC4, 0xD8);
    private static final Color CURSOR_COLOR = new Color(0xFF, 0x52, 0x52);
    private static final Color HUD_BG = new Color(0x16, 0x1A, 0x28, 240);
    private static final Color HUD_BORDER = new Color(0x38, 0x5C, 0x88);
    private static final Color HUD_TEXT = new Color(0xB0, 0xD4, 0xFF);

    private final ScanConfig config;
    private final ScanBuffer buffer;
    private final ScannerState state;
    private final ColormapRegistry colormapRegistry;

    private final Stroke cursorStroke = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{5.0f, 3.0f}, 0.0f);
    private final Stroke gridStroke = new BasicStroke(0.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{2.0f, 4.0f}, 0.0f);

    // Mouse hover tracking
    private int hoverCol = -1;
    private double hoverDepthMm = -1.0;
    private float hoverEcho = -1.0f;
    private boolean mouseInside = false;

    // Direct Raster Image for fastest possible blitting
    private BufferedImage bscanImage;
    private int[] imageRaster;
    private int allocatedImgWidth = 0;
    private int allocatedImgHeight = 0;

    public BScanPanel(ScanConfig config, ScanBuffer buffer, ScannerState state, ColormapRegistry colormapRegistry) {
        this.config = config;
        this.buffer = buffer;
        this.state = state;
        this.colormapRegistry = colormapRegistry;

        setPreferredSize(new Dimension(800, 450));
        setMinimumSize(new Dimension(400, 250));
        setOpaque(true);
        setBackground(BG_COLOR);

        setupMouseListeners();
    }

    private void setupMouseListeners() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateHoverInfo(e.getX(), e.getY());
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mouseInside = false;
                hoverCol = -1;
                repaint();
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    private void updateHoverInfo(int mouseX, int mouseY) {
        int marginLeft = 50;
        int marginRight = 20;
        int marginTop = 30;
        int marginBottom = 35;

        int plotW = Math.max(1, getWidth() - marginLeft - marginRight);
        int plotH = Math.max(1, getHeight() - marginTop - marginBottom);

        if (mouseX >= marginLeft && mouseX <= marginLeft + plotW &&
            mouseY >= marginTop && mouseY <= marginTop + plotH) {

            mouseInside = true;
            double fracX = (double) (mouseX - marginLeft) / plotW;
            double fracY = (double) (mouseY - marginTop) / plotH;

            int activeCols = state.getMode() == ScanMode.SWEEP ? buffer.getSweepCapacity() : buffer.getWaterfallWidth();
            hoverCol = (int) Math.clamp(fracX * activeCols, 0, activeCols - 1);

            double maxDepth = state.isZoomNear() ? ScannerState.NEAR_FIELD_ZOOM_MAX_MM : config.getMaxDepthMm();
            hoverDepthMm = fracY * maxDepth;

            int sampleIdx = config.getSampleIndexForDepth(hoverDepthMm);
            hoverEcho = buffer.getSample(state.getMode(), hoverCol, sampleIdx);
        } else {
            mouseInside = false;
            hoverCol = -1;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        int marginLeft = 50;
        int marginRight = 20;
        int marginTop = 30;
        int marginBottom = 35;

        int plotW = Math.max(10, w - marginLeft - marginRight);
        int plotH = Math.max(10, h - marginTop - marginBottom);

        // Header Title
        g2.setFont(new Font("SansSerif", Font.BOLD, 13));
        g2.setColor(Color.WHITE);
        String title;
        if (buffer.isLineScanActive() && state.getMode() == ScanMode.SWEEP) {
            title = String.format("2D B-Scan (2D LINE SCAN: %s-Axis | %.1f mm | Step: %.2f mm | %d Lines)",
                    buffer.getLineScanAxisLabel(), buffer.getLineScanLengthMm(),
                    buffer.getLineScanStepMm(), buffer.getSweepCapacity());
        } else {
            title = state.getMode() == ScanMode.SWEEP
                    ? "2D B-Scan (Mode: SWEEP PASS | [SPACE] to Freeze/Resume | Slide along Axis)"
                    : "2D B-Scan (Mode: CONTINUOUS WATERFALL | Real-Time Rolling View)";
        }
        g2.drawString(title, marginLeft, marginTop - 10);

        // Render 2D Raster Image
        renderBScanRaster(plotW, plotH);
        if (bscanImage != null) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(bscanImage, marginLeft, marginTop, plotW, plotH, null);
        }

        // Draw Plot Border
        g2.setColor(BORDER_COLOR);
        g2.drawRect(marginLeft, marginTop, plotW, plotH);

        // Draw Red Scanhead Cursor Line (Sweep mode only)
        if (state.getMode() == ScanMode.SWEEP) {
            int currentHead = buffer.getSweepIndex();
            int totalCap = buffer.getSweepCapacity();
            if (totalCap > 0) {
                double fracHead = Math.clamp((double) currentHead / totalCap, 0.0, 1.0);
                int headX = marginLeft + (int) (fracHead * plotW);
                g2.setColor(CURSOR_COLOR);
                g2.setStroke(cursorStroke);
                g2.drawLine(headX, marginTop, headX, marginTop + plotH);
            }
        }

        // Grid & Depth Axis (Y-axis: 0mm at top down to Max depth)
        drawDepthAxis(g2, marginLeft, marginTop, plotW, plotH);

        // Scan Line Axis (X-axis)
        drawScanLineAxis(g2, marginLeft, marginTop, plotW, plotH);

        // Top Right: Status Badge
        drawStatusBadge(g2, w - marginRight);

        // Top Left: Interactive Hover HUD Badge
        if (mouseInside && hoverCol >= 0) {
            drawHoverHud(g2, marginLeft + 10, marginTop + 10);
        }
    }

    private void renderBScanRaster(int targetW, int targetH) {
        ScanMode mode = state.getMode();
        int cols = mode == ScanMode.SWEEP ? buffer.getSweepCapacity() : buffer.getWaterfallWidth();
        int maxSample = state.isZoomNear()
                ? config.getSampleIndexForDepth(ScannerState.NEAR_FIELD_ZOOM_MAX_MM)
                : config.getNumSamples() - 1;
        int rows = maxSample + 1;

        if (cols <= 0 || rows <= 0) return;

        // Ensure raster image matches dimensions
        if (bscanImage == null || allocatedImgWidth != cols || allocatedImgHeight != rows) {
            bscanImage = new BufferedImage(cols, rows, BufferedImage.TYPE_INT_RGB);
            imageRaster = ((DataBufferInt) bscanImage.getRaster().getDataBuffer()).getData();
            allocatedImgWidth = cols;
            allocatedImgHeight = rows;
        }

        Colormap colormap = colormapRegistry.get(state.getColormap());
        int[] lut = colormap.getLut();
        int numSamplesTotal = config.getNumSamples();

        buffer.getLock().readLock().lock();
        try {
            float[] data = mode == ScanMode.SWEEP
                    ? buffer.getProcessedSweepBufferUnsafe()
                    : buffer.getWaterfallBufferUnsafe();

            for (int r = 0; r < rows; r++) {
                int rasterOffset = r * cols;
                for (int c = 0; c < cols; c++) {
                    int val = (int) data[c * numSamplesTotal + r];
                    imageRaster[rasterOffset + c] = lut[Math.clamp(val, 0, 255)];
                }
            }
        } finally {
            buffer.getLock().readLock().unlock();
        }
    }

    private void drawDepthAxis(Graphics2D g2, int marginLeft, int marginTop, int plotW, int plotH) {
        double maxDepth = state.isZoomNear() ? ScannerState.NEAR_FIELD_ZOOM_MAX_MM : config.getMaxDepthMm();
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10));

        int numDivs = 8;
        for (int i = 0; i <= numDivs; i++) {
            double frac = (double) i / numDivs;
            int y = marginTop + (int) (frac * plotH);

            g2.setColor(GRID_COLOR);
            g2.setStroke(gridStroke);
            g2.drawLine(marginLeft, y, marginLeft + plotW, y);

            double depthMm = frac * maxDepth;
            g2.setColor(TEXT_COLOR);
            String label = String.format("%.0f", depthMm);
            int strW = g2.getFontMetrics().stringWidth(label);
            g2.drawString(label, marginLeft - strW - 6, y + 4);
        }

        // Y-axis Title
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.setColor(Color.WHITE);
        g2.drawString("Depth", 6, marginTop + plotH / 2 - 8);
        g2.drawString("(mm)", 8, marginTop + plotH / 2 + 6);
    }

    private void drawScanLineAxis(Graphics2D g2, int marginLeft, int marginTop, int plotW, int plotH) {
        int activeCols = state.getMode() == ScanMode.SWEEP ? buffer.getSweepCapacity() : buffer.getWaterfallWidth();
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10));

        boolean isLineScan = buffer.isLineScanActive() && state.getMode() == ScanMode.SWEEP;
        double lineLengthMm = buffer.getLineScanLengthMm();
        String axisLabel = buffer.getLineScanAxisLabel();

        int numDivs = 10;
        for (int i = 0; i <= numDivs; i++) {
            double frac = (double) i / numDivs;
            int x = marginLeft + (int) (frac * plotW);

            g2.setColor(GRID_COLOR);
            g2.setStroke(gridStroke);
            g2.drawLine(x, marginTop, x, marginTop + plotH);

            g2.setColor(TEXT_COLOR);
            String label;
            if (isLineScan && lineLengthMm > 0) {
                double posMm = frac * lineLengthMm;
                label = lineLengthMm <= 15.0 ? String.format("%.1f", posMm) : String.format("%.0f", posMm);
            } else {
                int col = (int) (frac * activeCols);
                label = String.valueOf(col);
            }
            int strW = g2.getFontMetrics().stringWidth(label);
            g2.drawString(label, x - strW / 2, marginTop + plotH + 16);
        }

        // X-axis Title
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.setColor(Color.WHITE);
        String label;
        if (isLineScan && lineLengthMm > 0) {
            label = String.format("%s-Axis Position (mm) [Total: %.1f mm]", axisLabel, lineLengthMm);
        } else if (state.getMode() == ScanMode.SWEEP) {
            label = "Scan Position / Sample Lines";
        } else {
            label = "Rolling Sweep History (Lines)";
        }
        int strW = g2.getFontMetrics().stringWidth(label);
        g2.drawString(label, marginLeft + (plotW - strW) / 2, marginTop + plotH + 30);
    }

    private void drawStatusBadge(Graphics2D g2, int rightEdge) {
        String text;
        Color badgeBg;
        Color badgeBorder;
        Color textColor;

        if (buffer.isLineScanActive() && state.getMode() == ScanMode.SWEEP) {
            int cur = buffer.getSweepIndex();
            int total = buffer.getSweepCapacity();
            if (cur < total) {
                int pct = (int) Math.clamp(Math.round(((double) cur / Math.max(1, total)) * 100.0), 0, 99);
                text = String.format("LINE SCANNING (%d%%)", pct);
                badgeBg = new Color(0x10, 0x2A, 0x36);
                badgeBorder = new Color(0x00, 0xC8, 0x53);
                textColor = new Color(0x00, 0xE5, 0xFF);
            } else {
                text = "LINE SCAN COMPLETE";
                badgeBg = new Color(0x10, 0x30, 0x18);
                badgeBorder = new Color(0x00, 0xCC, 0x66);
                textColor = new Color(0x33, 0xFF, 0x99);
            }
        } else {
            boolean recording = state.isRecording();
            text = recording ? "RECORDING" : "FROZEN / PAUSED";
            badgeBg = recording ? new Color(0x11, 0x28, 0x11) : new Color(0x2E, 0x26, 0x10);
            badgeBorder = recording ? new Color(0x00, 0xAA, 0x44) : new Color(0xAA, 0x88, 0x00);
            textColor = recording ? new Color(0x00, 0xFF, 0x66) : new Color(0xFF, 0xAA, 0x00);
        }

        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        int strW = g2.getFontMetrics().stringWidth(text);
        int badgeW = strW + 16;
        int badgeH = 22;
        int x = rightEdge - badgeW - 10;
        int y = 35;

        g2.setColor(badgeBg);
        g2.fillRoundRect(x, y, badgeW, badgeH, 6, 6);
        g2.setColor(badgeBorder);
        g2.drawRoundRect(x, y, badgeW, badgeH, 6, 6);
        g2.setColor(textColor);
        g2.drawString(text, x + 8, y + 15);
    }

    private void drawHoverHud(Graphics2D g2, int x, int y) {
        String text;
        if (buffer.isLineScanActive() && state.getMode() == ScanMode.SWEEP && buffer.getLineScanLengthMm() > 0) {
            double posMm = ((double) hoverCol / Math.max(1, buffer.getSweepCapacity() - 1)) * buffer.getLineScanLengthMm();
            text = String.format("Depth: %.1f mm | %s: %.2f mm (Line %d) | Echo: %.0f",
                    hoverDepthMm, buffer.getLineScanAxisLabel(), posMm, hoverCol + 1, hoverEcho);
        } else {
            text = String.format("Cursor: %.1f mm | Line %d | Echo: %.0f", hoverDepthMm, hoverCol, hoverEcho);
        }
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        int strW = g2.getFontMetrics().stringWidth(text);
        int hudW = strW + 14;
        int hudH = 20;

        g2.setColor(HUD_BG);
        g2.fillRoundRect(x, y, hudW, hudH, 6, 6);
        g2.setColor(HUD_BORDER);
        g2.drawRoundRect(x, y, hudW, hudH, 6, 6);
        g2.setColor(HUD_TEXT);
        g2.drawString(text, x + 7, y + 14);
    }

    /**
     * Captures a high-resolution snapshot of the full B-Scan display (including calibrated axes,
     * millimeter depth ticks, header title, and ultrasound raster) for export.
     */
    public BufferedImage captureSnapshot() {
        int w = Math.max(getWidth(), 900);
        int h = Math.max(getHeight(), 500);
        BufferedImage snapshot = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = snapshot.createGraphics();
        paintComponent(g2);
        g2.dispose();
        return snapshot;
    }
}
