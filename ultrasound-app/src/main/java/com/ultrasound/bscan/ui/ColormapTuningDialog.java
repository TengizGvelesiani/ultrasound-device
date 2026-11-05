package com.ultrasound.bscan.ui;

import com.ultrasound.bscan.colormap.Colormap;
import com.ultrasound.bscan.colormap.ColormapRegistry;
import com.ultrasound.bscan.colormap.ColormapType;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.model.VolumeScanConfig;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;

/**
 * Interactive dialog to inspect, customize, and fine-tune colormaps,
 * depth contrast windows (Color Min / Color Max), gamma, and inversion in real time.
 */
public final class ColormapTuningDialog extends JDialog {

    private static final Color BG_DARK = new Color(0x18, 0x1A, 0x24);
    private static final Color BG_PANEL = new Color(0x22, 0x26, 0x34);
    private static final Color BORDER_COLOR = new Color(0x36, 0x3E, 0x54);
    private static final Color TEXT_WHITE = new Color(0xEE, 0xF2, 0xFF);
    private static final Color ACCENT_CYAN = new Color(0x00, 0xDD, 0xFF);
    private static final Color BTN_BG = new Color(0x2C, 0x36, 0x48);

    private final ScannerState state;
    private final Volume3DPanel volume3DPanel;
    private final ColormapRegistry colormapRegistry;

    private final JComboBox<ColormapType> cmbColormap = new JComboBox<>(ColormapType.values());
    private final JCheckBox chkInvert = new JCheckBox("Invert Colormap Colors", false);
    private final JCheckBox chkLockGate = new JCheckBox("Lock Color Scale to Echo Gate", true);

    // Color depth limits (in 0.5 mm units, e.g. 24 = 12.0 mm, 32 = 16.0 mm)
    private final JSlider sldColorMin = new JSlider(10, 60, 24);
    private final JSlider sldColorMax = new JSlider(16, 80, 32);
    private final JLabel lblColorMin = new JLabel("Color Min Depth: 12.0 mm");
    private final JLabel lblColorMax = new JLabel("Color Max Depth: 16.0 mm");

    private final JSlider sldContrast = new JSlider(5, 30, (int) (ScannerState.DEFAULT_CONTRAST * 10));
    private final JSlider sldGamma = new JSlider(3, 25, 10);
    private final JLabel lblContrast = new JLabel(String.format("Contrast: %.1fx", ScannerState.DEFAULT_CONTRAST));
    private final JLabel lblGamma = new JLabel("Midtone Gamma: 1.0x");

    private final GradientPreviewPanel gradientPreview = new GradientPreviewPanel();

    public ColormapTuningDialog(JFrame parent, ScannerState state, Volume3DPanel volume3DPanel, ColormapRegistry colormapRegistry) {
        super(parent, "Colormap & Depth Scale Tuning", false);
        this.state = state;
        this.volume3DPanel = volume3DPanel;
        this.colormapRegistry = colormapRegistry;

        setSize(480, 560);
        setMinimumSize(new Dimension(420, 500));
        setLocationRelativeTo(parent);
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout(8, 8));

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBackground(BG_DARK);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        // 1. Colormap Preset Section
        JPanel pnlColormap = createSectionPanel("1. Color Palette");
        pnlColormap.setLayout(new BoxLayout(pnlColormap, BoxLayout.Y_AXIS));

        ModernUi.styleComboBox(cmbColormap);
        cmbColormap.setSelectedItem(state.getColormap());
        pnlColormap.add(cmbColormap);
        pnlColormap.add(Box.createVerticalStrut(8));

        gradientPreview.setPreferredSize(new Dimension(420, 24));
        gradientPreview.setMaximumSize(new Dimension(500, 24));
        pnlColormap.add(gradientPreview);
        pnlColormap.add(Box.createVerticalStrut(8));

        chkInvert.setForeground(TEXT_WHITE);
        chkInvert.setOpaque(false);
        chkInvert.setSelected(state.isColormapInverted());
        chkInvert.setFocusable(false);
        pnlColormap.add(chkInvert);

        mainPanel.add(pnlColormap);
        mainPanel.add(Box.createVerticalStrut(10));

        // 2. Depth Scale Window Section (Fixes 8mm-18mm stretch issue)
        JPanel pnlDepth = createSectionPanel("2. Colormap Depth Window (Contrast Framing)");
        pnlDepth.setLayout(new BoxLayout(pnlDepth, BoxLayout.Y_AXIS));

        chkLockGate.setForeground(ACCENT_CYAN);
        chkLockGate.setFont(new Font("SansSerif", Font.BOLD, 11));
        chkLockGate.setOpaque(false);
        chkLockGate.setSelected(state.isColorRangeLockToGate());
        chkLockGate.setFocusable(false);
        pnlDepth.add(chkLockGate);
        pnlDepth.add(Box.createVerticalStrut(6));

        setupSlider(sldColorMin, lblColorMin);
        setupSlider(sldColorMax, lblColorMax);

        updateSliderFromState();

        pnlDepth.add(lblColorMin);
        pnlDepth.add(sldColorMin);
        pnlDepth.add(Box.createVerticalStrut(4));
        pnlDepth.add(lblColorMax);
        pnlDepth.add(sldColorMax);
        pnlDepth.add(Box.createVerticalStrut(8));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 2));
        btnRow.setOpaque(false);
        JButton btnTarget13mm = createSmallButton("Target 13-14mm Preset", new Color(0x1B, 0x4D, 0x3E));
        JButton btnAutoFit = createSmallButton("Auto-Fit to Object Data", BTN_BG);
        btnRow.add(btnTarget13mm);
        btnRow.add(btnAutoFit);
        pnlDepth.add(btnRow);

        mainPanel.add(pnlDepth);
        mainPanel.add(Box.createVerticalStrut(10));

        // 3. Contrast & Midtone Gamma Section
        JPanel pnlContrast = createSectionPanel("3. Brightness & Midtone Gamma");
        pnlContrast.setLayout(new BoxLayout(pnlContrast, BoxLayout.Y_AXIS));

        setupSlider(sldContrast, lblContrast);
        setupSlider(sldGamma, lblGamma);

        sldContrast.setValue((int) Math.round(state.getContrast() * 10));
        lblContrast.setText(String.format("Contrast / Brightness: %.1fx", state.getContrast()));

        sldGamma.setValue((int) Math.round(state.getColormapGamma() * 10));
        lblGamma.setText(String.format("Midtone Gamma: %.1fx", state.getColormapGamma()));

        pnlContrast.add(lblContrast);
        pnlContrast.add(sldContrast);
        pnlContrast.add(Box.createVerticalStrut(4));
        pnlContrast.add(lblGamma);
        pnlContrast.add(sldGamma);

        mainPanel.add(pnlContrast);

        add(mainPanel, BorderLayout.CENTER);

        // Bottom Close button
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        bottomBar.setBackground(BG_DARK);
        JButton btnClose = new ModernUi.RoundedButton("Close", new Color(0x28, 0x58, 0x88), new Color(0x38, 0x78, 0xB8), 6);
        btnClose.setPreferredSize(new Dimension(90, 30));
        btnClose.addActionListener(e -> setVisible(false));
        bottomBar.add(btnClose);
        add(bottomBar, BorderLayout.SOUTH);

        // Listeners
        cmbColormap.addActionListener(e -> {
            ColormapType sel = (ColormapType) cmbColormap.getSelectedItem();
            if (sel != null) {
                state.setColormap(sel);
                gradientPreview.repaint();
                volume3DPanel.repaint();
            }
        });

        chkInvert.addActionListener(e -> {
            state.setColormapInverted(chkInvert.isSelected());
            gradientPreview.repaint();
            volume3DPanel.repaint();
        });

        chkLockGate.addActionListener(e -> {
            state.setColorRangeLockToGate(chkLockGate.isSelected());
            updateSliderFromState();
            volume3DPanel.repaint();
        });

        sldColorMin.addChangeListener(e -> {
            double minMm = sldColorMin.getValue() / 2.0;
            if (minMm >= sldColorMax.getValue() / 2.0) {
                sldColorMax.setValue((int) Math.round((minMm + 0.5) * 2.0));
            }
            if (chkLockGate.isSelected()) {
                state.getVolumeConfig().setGateMinDepthMm(minMm);
            }
            state.setColorMinDepthMm(minMm);
            lblColorMin.setText(String.format("Color Min Depth: %.1f mm", minMm));
            volume3DPanel.repaint();
        });

        sldColorMax.addChangeListener(e -> {
            double maxMm = sldColorMax.getValue() / 2.0;
            if (maxMm <= sldColorMin.getValue() / 2.0) {
                sldColorMin.setValue((int) Math.round((maxMm - 0.5) * 2.0));
            }
            if (chkLockGate.isSelected()) {
                state.getVolumeConfig().setGateMaxDepthMm(maxMm);
            }
            state.setColorMaxDepthMm(maxMm);
            lblColorMax.setText(String.format("Color Max Depth: %.1f mm", maxMm));
            volume3DPanel.repaint();
        });

        btnTarget13mm.addActionListener(e -> {
            state.getVolumeConfig().setGateMinDepthMm(12.0);
            state.getVolumeConfig().setGateMaxDepthMm(16.0);
            state.setColorRangeLockToGate(true);
            chkLockGate.setSelected(true);
            updateSliderFromState();
            volume3DPanel.repaint();
        });

        btnAutoFit.addActionListener(e -> {
            volume3DPanel.autoFitColorRangeToObject();
            chkLockGate.setSelected(state.isColorRangeLockToGate());
            updateSliderFromState();
        });

        sldContrast.addChangeListener(e -> {
            double c = sldContrast.getValue() / 10.0;
            state.setContrast(c);
            lblContrast.setText(String.format("Contrast / Brightness: %.1fx", c));
            volume3DPanel.repaint();
        });

        sldGamma.addChangeListener(e -> {
            double g = sldGamma.getValue() / 10.0;
            state.setColormapGamma(g);
            lblGamma.setText(String.format("Midtone Gamma: %.1fx", g));
            volume3DPanel.repaint();
        });
    }

    private void updateSliderFromState() {
        double minMm = state.getColorMinDepthMm();
        double maxMm = state.getColorMaxDepthMm();
        sldColorMin.setValue((int) Math.round(minMm * 2.0));
        sldColorMax.setValue((int) Math.round(maxMm * 2.0));
        lblColorMin.setText(String.format("Color Min Depth: %.1f mm", minMm));
        lblColorMax.setText(String.format("Color Max Depth: %.1f mm", maxMm));
    }

    private JPanel createSectionPanel(String title) {
        JPanel pnl = new JPanel();
        pnl.setBackground(BG_PANEL);
        pnl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createTitledBorder(
                        BorderFactory.createEmptyBorder(4, 8, 8, 8),
                        title, 0, 0, new Font("SansSerif", Font.BOLD, 12), TEXT_WHITE)
        ));
        return pnl;
    }

    private void setupSlider(JSlider slider, JLabel label) {
        slider.setPreferredSize(new Dimension(380, 24));
        slider.setOpaque(false);
        label.setForeground(TEXT_WHITE);
        label.setFont(new Font("SansSerif", Font.PLAIN, 11));
    }

    private JButton createSmallButton(String label, Color bg) {
        ModernUi.RoundedButton btn = new ModernUi.RoundedButton(label, bg, ModernUi.lighten(bg, 0.35f), 5);
        btn.setPreferredSize(new Dimension(180, 26));
        btn.setFont(new Font("SansSerif", Font.BOLD, 10));
        btn.setFocusable(false);
        return btn;
    }

    private final class GradientPreviewPanel extends JPanel {
        GradientPreviewPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            Colormap cm = colormapRegistry.get(state.getColormap());
            int[] lut = cm.getLut();
            boolean inverted = state.isColormapInverted();

            for (int x = 0; x < w; x++) {
                double frac = (double) x / (w - 1);
                int rawIdx = Math.clamp((int) Math.round(frac * 255.0), 0, 255);
                int idx = inverted ? (255 - rawIdx) : rawIdx;
                g.setColor(new Color(lut[idx]));
                g.fillRect(x, 0, 1, h);
            }

            g.setColor(BORDER_COLOR);
            g.drawRect(0, 0, w - 1, h - 1);
        }
    }
}
