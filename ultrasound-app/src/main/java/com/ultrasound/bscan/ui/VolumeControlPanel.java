package com.ultrasound.bscan.ui;

import com.ultrasound.bscan.model.Render3DStyle;
import com.ultrasound.bscan.model.ScannerState;
import com.ultrasound.bscan.model.ViewMode;
import com.ultrasound.bscan.model.VolumeBuffer;
import com.ultrasound.bscan.model.VolumeScanConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;

/**
 * Self-sufficient control bar for 2.5D / 3D volumetric scanning,
 * depth-gating, colormap customization, contrast/brightness, and view mode switching.
 */
public final class VolumeControlPanel extends JPanel {

    private static final Color BG_COLOR = new Color(0x18, 0x18, 0x20);
    private static final Color BTN_MODE_COLOR = new Color(0x2E, 0x5B, 0x82);
    private static final Color BTN_COLOR = new Color(0x2B, 0x35, 0x40);
    private static final Color BTN_TUNE_COLOR = new Color(0x30, 0x4B, 0x6E);
    private static final Color BTN_CLEAR_COLOR = new Color(0x60, 0x2A, 0x2A);
    private static final Color BTN_SAVE_COLOR = new Color(0x1B, 0x4D, 0x3E);
    private static final Color TEXT_COLOR = Color.WHITE;

    private final ScannerState state;
    private final VolumeBuffer volumeBuffer;

    private final JButton btnSwitchMode = createButton("2D Mode [TAB]", BTN_MODE_COLOR);
    private final JButton btnRecord = createButton("Pause (Space)", BTN_COLOR);
    private final JButton btnStyle = createButton("Style [Y]", BTN_COLOR);
    private final JButton btnColormap = createButton("Map: Rainbow [V]", BTN_COLOR);
    private final JButton btnTuneColors = createButton("Tune [K]", BTN_TUNE_COLOR);
    private final JButton btnInvert = createButton("Invert [I]", BTN_COLOR);
    private final JButton btnSave = createButton("Save (S)", BTN_SAVE_COLOR);
    private final JButton btnOpenFolder = createButton("Folder", BTN_COLOR);
    private final JButton btnClear3D = createButton("Clear (C)", BTN_CLEAR_COLOR);

    private final JComboBox<String> cmbGrid = new JComboBox<>(new String[]{"100 x 41 (10x40mm Standard)", "50 x 50 (Fast)", "100 x 100 (High-Res)"});
    private final JComboBox<Volume3DPanel.CameraPreset> cmbViewPreset = new JComboBox<>(Volume3DPanel.CameraPreset.values());

    private final JSlider sldGateMin = new JSlider(5, 30, 11);
    private final JSlider sldGateMax = new JSlider(8, 40, 16);
    private final JSlider sldExaggeration = new JSlider(5, 40, 18);
    private final JSlider sldContrast = new JSlider(5, 30, (int) (ScannerState.DEFAULT_CONTRAST * 10));

    private final JLabel lblGateMin = new JLabel("Gate Min: 11 mm");
    private final JLabel lblGateMax = new JLabel("Gate Max: 16 mm");
    private final JLabel lblExag = new JLabel("Z-Exag: 1.8x");
    private final JLabel lblContrast = new JLabel(String.format("Contrast: %.1fx", ScannerState.DEFAULT_CONTRAST));

    public VolumeControlPanel(ScannerState state, VolumeBuffer volumeBuffer) {
        this.state = state;
        this.volumeBuffer = volumeBuffer;

        setLayout(new GridLayout(3, 1, 4, 4));
        setBackground(BG_COLOR);
        setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        ModernUi.styleComboBox(cmbGrid);
        ModernUi.styleComboBox(cmbViewPreset);

        // Row 1: Primary Action Buttons & Color Tuning
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        row1.setBackground(BG_COLOR);

        btnSwitchMode.setFont(btnSwitchMode.getFont().deriveFont(Font.BOLD));
        btnSwitchMode.setForeground(new Color(0x88, 0xDD, 0xFF));

        btnTuneColors.setFont(btnTuneColors.getFont().deriveFont(Font.BOLD));
        btnTuneColors.setForeground(new Color(0xAA, 0xEE, 0xFF));

        btnSave.setFont(btnSave.getFont().deriveFont(Font.BOLD));
        btnSave.setForeground(new Color(0x88, 0xFF, 0xCC));

        btnColormap.setText("Map: " + state.getColormap().getLabel() + " [V]");

        row1.add(btnSwitchMode);
        row1.add(btnRecord);
        row1.add(btnStyle);
        row1.add(btnColormap);
        row1.add(btnTuneColors);
        row1.add(btnInvert);
        row1.add(btnSave);
        row1.add(btnOpenFolder);
        row1.add(btnClear3D);

        // Row 2: Grid Configuration, Camera Presets & Z-Exaggeration
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 2));
        row2.setBackground(BG_COLOR);

        JLabel lblGrid = new JLabel("Grid:");
        lblGrid.setForeground(TEXT_COLOR);
        row2.add(lblGrid);
        cmbGrid.setSelectedIndex(0);
        cmbGrid.setFocusable(false);
        row2.add(cmbGrid);


        JLabel lblView = new JLabel("Camera:");
        lblView.setForeground(TEXT_COLOR);
        row2.add(lblView);
        cmbViewPreset.setFocusable(false);
        row2.add(cmbViewPreset);

        setupSlider(sldExaggeration, lblExag);
        sldExaggeration.setPreferredSize(new Dimension(100, 24));
        row2.add(lblExag);
        row2.add(sldExaggeration);

        // Row 3: Live Depth Gate & Contrast / Brightness Sliders
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 2));
        row3.setBackground(BG_COLOR);

        setupSlider(sldGateMin, lblGateMin);
        setupSlider(sldGateMax, lblGateMax);
        setupSlider(sldContrast, lblContrast);
        sldContrast.setPreferredSize(new Dimension(150, 24));
        lblContrast.setForeground(new Color(0x88, 0xEE, 0xFF));
        lblContrast.setFont(new Font("SansSerif", Font.BOLD, 11));

        row3.add(lblGateMin);
        row3.add(sldGateMin);
        row3.add(lblGateMax);
        row3.add(sldGateMax);
        row3.add(lblContrast);
        row3.add(sldContrast);

        add(row1);
        add(row2);
        add(row3);

        setupListeners();
    }

    private void setupSlider(JSlider slider, JLabel label) {
        slider.setPreferredSize(new Dimension(110, 24));
        slider.setOpaque(false);
        label.setForeground(TEXT_COLOR);
        label.setFont(new Font("SansSerif", Font.PLAIN, 11));
    }

    private void setupListeners() {
        cmbGrid.addActionListener(e -> {
            int idx = cmbGrid.getSelectedIndex();
            if (idx == 0) {
                state.getVolumeConfig().setGridX(100);
                state.getVolumeConfig().setGridY(41);
                state.getVolumeConfig().setStepSizeXMm(0.1);
                state.getVolumeConfig().setStepSizeYMm(1.0);
            } else if (idx == 1) {
                state.getVolumeConfig().setGridX(50);
                state.getVolumeConfig().setGridY(50);
                state.getVolumeConfig().setStepSizeXMm(0.2);
                state.getVolumeConfig().setStepSizeYMm(0.8);
            } else {
                state.getVolumeConfig().setGridX(100);
                state.getVolumeConfig().setGridY(100);
                state.getVolumeConfig().setStepSizeXMm(0.1);
                state.getVolumeConfig().setStepSizeYMm(0.4);
            }
            volumeBuffer.reallocate(state.getVolumeConfig());
        });


        sldGateMin.addChangeListener(e -> {
            int minVal = sldGateMin.getValue();
            if (minVal >= sldGateMax.getValue()) {
                sldGateMax.setValue(minVal + 1);
            }
            state.getVolumeConfig().setGateMinDepthMm(minVal);
            if (state.isColorRangeLockToGate()) {
                state.setColorMinDepthMm(minVal);
            }
            lblGateMin.setText(String.format("Gate Min: %d mm", minVal));
        });

        sldGateMax.addChangeListener(e -> {
            int maxVal = sldGateMax.getValue();
            if (maxVal <= sldGateMin.getValue()) {
                sldGateMin.setValue(maxVal - 1);
            }
            state.getVolumeConfig().setGateMaxDepthMm(maxVal);
            if (state.isColorRangeLockToGate()) {
                state.setColorMaxDepthMm(maxVal);
            }
            lblGateMax.setText(String.format("Gate Max: %d mm", maxVal));
        });

        sldExaggeration.addChangeListener(e -> {
            double exag = sldExaggeration.getValue() / 10.0;
            state.setDepthExaggeration(exag);
            lblExag.setText(String.format("Z-Exag: %.1fx", exag));
        });

        sldContrast.addChangeListener(e -> {
            double contrastVal = sldContrast.getValue() / 10.0;
            state.setContrast(contrastVal);
            lblContrast.setText(String.format("Contrast: %.1fx", contrastVal));
        });

        btnInvert.addActionListener(e -> state.toggleColormapInverted());

        state.addListener(s -> {
            btnSwitchMode.setText(s.getViewMode() == ViewMode.MODE_2D ? "3D Mode [TAB]" : "2D Mode [TAB]");
            btnRecord.setText(s.isRecording() ? "Pause (Space)" : "Resume (Space)");
            btnStyle.setText("Style [Y]");
            btnColormap.setText("Map: " + s.getColormap().getLabel() + " [V]");
            btnInvert.setText(s.isColormapInverted() ? "Invert: ON" : "Invert: OFF");

            int contrastInt = (int) Math.round(s.getContrast() * 10);
            if (sldContrast.getValue() != contrastInt) {
                sldContrast.setValue(contrastInt);
                lblContrast.setText(String.format("Contrast: %.1fx", s.getContrast()));
            }

            int gateMinInt = (int) Math.round(s.getVolumeConfig().getGateMinDepthMm());
            if (sldGateMin.getValue() != gateMinInt) {
                sldGateMin.setValue(gateMinInt);
                lblGateMin.setText(String.format("Gate Min: %d mm", gateMinInt));
            }

            int gateMaxInt = (int) Math.round(s.getVolumeConfig().getGateMaxDepthMm());
            if (sldGateMax.getValue() != gateMaxInt) {
                sldGateMax.setValue(gateMaxInt);
                lblGateMax.setText(String.format("Gate Max: %d mm", gateMaxInt));
            }
        });
    }

    private JButton createButton(String label, Color bg) {
        ModernUi.RoundedButton btn = new ModernUi.RoundedButton(label, bg, ModernUi.lighten(bg, 0.35f), 6);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 9));
        btn.setPreferredSize(new Dimension(105, 25));
        btn.setMargin(new Insets(2, 4, 2, 4));
        btn.setFocusable(false);
        return btn;
    }

    public void onSwitchModeClicked(Runnable r) { btnSwitchMode.addActionListener(e -> r.run()); }
    public void onRecordClicked(Runnable r) { btnRecord.addActionListener(e -> r.run()); }
    public void onStyleClicked(Runnable r) { btnStyle.addActionListener(e -> r.run()); }
    public void onColormapClicked(Runnable r) { btnColormap.addActionListener(e -> r.run()); }
    public void onTuneColorsClicked(Runnable r) { btnTuneColors.addActionListener(e -> r.run()); }
    public void onSaveClicked(Runnable r) { btnSave.addActionListener(e -> r.run()); }
    public void onOpenFolderClicked(Runnable r) { btnOpenFolder.addActionListener(e -> r.run()); }
    public void onClear3DClicked(Runnable r) { btnClear3D.addActionListener(e -> r.run()); }

    public void onCameraPresetSelected(java.util.function.Consumer<Volume3DPanel.CameraPreset> c) {
        cmbViewPreset.addActionListener(e -> {
            Volume3DPanel.CameraPreset p = (Volume3DPanel.CameraPreset) cmbViewPreset.getSelectedItem();
            if (p != null) c.accept(p);
        });
    }

    public void setCameraPresetSelection(Volume3DPanel.CameraPreset preset) {
        cmbViewPreset.setSelectedItem(preset);
    }

    public void setGateMinSelection(int minVal) {
        if (sldGateMin.getValue() != minVal) {
            sldGateMin.setValue(minVal);
            lblGateMin.setText(String.format("Gate Min: %d mm", minVal));
        }
    }

    public void setGateMaxSelection(int maxVal) {
        if (sldGateMax.getValue() != maxVal) {
            sldGateMax.setValue(maxVal);
            lblGateMax.setText(String.format("Gate Max: %d mm", maxVal));
        }
    }
}
