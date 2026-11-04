package com.ultrasound.bscan.ui;

import com.ultrasound.bscan.model.AScanFrame;
import com.ultrasound.bscan.model.PeakEcho;
import com.ultrasound.bscan.model.ScanConfig;
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

/**
 * High-performance real-time A-Scan plot rendering the 1D echo envelope.
 */
public final class AScanPanel extends JPanel {

    private static final Color BG_COLOR = new Color(0x18, 0x18, 0x20);
    private static final Color BORDER_COLOR = new Color(0x33, 0x33, 0x40);
    private static final Color GRID_COLOR = new Color(0x28, 0x28, 0x35);
    private static final Color TEXT_COLOR = new Color(0xAA, 0xAA, 0xBB);
    private static final Color SIGNAL_COLOR = new Color(0x00, 0xFF, 0xCC);
    private static final Color SQUELCH_COLOR = new Color(0xFF, 0x44, 0x44);
    private static final Color PEAK_COLOR = new Color(0xFF, 0xDD, 0x55);
    private static final Color BADGE_BG = new Color(0x22, 0x22, 0x2A);

    private final ScanConfig config;
    private final ScannerState state;

    private final Stroke waveStroke = new BasicStroke(1.8f);
    private final Stroke squelchStroke = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f, 4.0f}, 0.0f);
    private final Stroke gridStroke = new BasicStroke(0.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{2.0f, 4.0f}, 0.0f);

    public AScanPanel(ScanConfig config, ScannerState state) {
        this.config = config;
        this.state = state;
        setPreferredSize(new Dimension(800, 180));
        setMinimumSize(new Dimension(400, 120));
        setOpaque(true);
        setBackground(BG_COLOR);
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
        int marginTop = 25;
        int marginBottom = 30;

        int plotW = Math.max(10, w - marginLeft - marginRight);
        int plotH = Math.max(10, h - marginTop - marginBottom);

        // Background & plot box
        g2.setColor(BG_COLOR);
        g2.fillRect(0, 0, w, h);
        g2.setColor(BORDER_COLOR);
        g2.drawRect(marginLeft, marginTop, plotW, plotH);

        // Title
        g2.setFont(new Font("SansSerif", Font.BOLD, 12));
        g2.setColor(Color.WHITE);
        g2.drawString("Real-Time A-Scan (Acoustic Reflection Envelope)", marginLeft, marginTop - 8);

        // Grid & Ticks
        double maxDisplayDepth = state.isZoomNear() ? ScannerState.NEAR_FIELD_ZOOM_MAX_MM : config.getMaxDepthMm();
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10));

        // Depth Grid (X axis)
        int numXDivs = 10;
        g2.setStroke(gridStroke);
        for (int i = 0; i <= numXDivs; i++) {
            double frac = (double) i / numXDivs;
            int x = marginLeft + (int) (frac * plotW);
            g2.setColor(GRID_COLOR);
            g2.drawLine(x, marginTop, x, marginTop + plotH);

            double depthMm = frac * maxDisplayDepth;
            g2.setColor(TEXT_COLOR);
            String label = String.format("%.0f", depthMm);
            int strW = g2.getFontMetrics().stringWidth(label);
            g2.drawString(label, x - strW / 2, marginTop + plotH + 15);
        }

        // Amplitude Grid (Y axis)
        int[] ampLevels = { 0, 64, 128, 192, 255 };
        for (int amp : ampLevels) {
            double frac = amp / 255.0;
            int y = marginTop + plotH - (int) (frac * plotH);
            g2.setColor(GRID_COLOR);
            g2.drawLine(marginLeft, y, marginLeft + plotW, y);

            g2.setColor(TEXT_COLOR);
            String label = String.valueOf(amp);
            int strW = g2.getFontMetrics().stringWidth(label);
            g2.drawString(label, marginLeft - strW - 6, y + 4);
        }

        // Axis Labels
        g2.setColor(TEXT_COLOR);
        g2.drawString("Depth (mm)", marginLeft + plotW / 2 - 25, h - 5);

        // Draw Squelch Threshold Line
        double squelch = state.getSquelch();
        int squelchY = marginTop + plotH - (int) (Math.clamp(squelch / 255.0, 0.0, 1.0) * plotH);
        g2.setColor(SQUELCH_COLOR);
        g2.setStroke(squelchStroke);
        g2.drawLine(marginLeft, squelchY, marginLeft + plotW, squelchY);

        // Draw Signal Waveform
        AScanFrame frame = state.getLatestFrame();
        if (frame != null) {
            float[] sig = frame.getSignal();
            int maxSampleIdx = state.isZoomNear()
                    ? config.getSampleIndexForDepth(ScannerState.NEAR_FIELD_ZOOM_MAX_MM)
                    : sig.length - 1;

            int[] xPoints = new int[maxSampleIdx + 1];
            int[] yPoints = new int[maxSampleIdx + 1];

            for (int i = 0; i <= maxSampleIdx; i++) {
                double fracX = (double) i / maxSampleIdx;
                xPoints[i] = marginLeft + (int) (fracX * plotW);
                double fracY = Math.clamp(sig[i] / 255.0f, 0.0f, 1.0f);
                yPoints[i] = marginTop + plotH - (int) (fracY * plotH);
            }

            g2.setColor(SIGNAL_COLOR);
            g2.setStroke(waveStroke);
            g2.drawPolyline(xPoints, yPoints, maxSampleIdx + 1);

            // Peak Echo Marker & Badge
            PeakEcho peak = frame.getPeakEcho();
            if (peak.detected() && peak.depthMm() <= maxDisplayDepth) {
                double peakFracX = peak.depthMm() / maxDisplayDepth;
                int px = marginLeft + (int) (peakFracX * plotW);
                int py = marginTop + plotH - (int) (Math.clamp(peak.amplitude() / 255.0f, 0.0f, 1.0f) * plotH);

                // Highlight circle
                g2.setColor(Color.RED);
                g2.fillOval(px - 5, py - 5, 10, 10);
                g2.setColor(PEAK_COLOR);
                g2.drawOval(px - 6, py - 6, 12, 12);

                // Peak info badge
                String peakText = String.format("Echo Peak: %.1f mm | Amp: %.0f/255", peak.depthMm(), peak.amplitude());
                drawBadge(g2, peakText, w - marginRight - 190, marginTop + 5, PEAK_COLOR);
            } else {
                drawBadge(g2, "Echo Peak: None", w - marginRight - 120, marginTop + 5, TEXT_COLOR);
            }
        } else {
            drawBadge(g2, "Waiting for signal...", w - marginRight - 140, marginTop + 5, TEXT_COLOR);
        }
    }

    private void drawBadge(Graphics2D g2, String text, int x, int y, Color textColor) {
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        int strW = g2.getFontMetrics().stringWidth(text);
        g2.setColor(BADGE_BG);
        g2.fillRoundRect(x - 5, y, strW + 10, 18, 6, 6);
        g2.setColor(BORDER_COLOR);
        g2.drawRoundRect(x - 5, y, strW + 10, 18, 6, 6);
        g2.setColor(textColor);
        g2.drawString(text, x, y + 13);
    }
}
