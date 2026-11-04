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

/**
 * Self-sufficient control bar for 2.5D / 3D volumetric scanning,
 * depth-gating, camera styles, and view mode switching.
 */
public final class VolumeControlPanel extends JPanel {

    private static final Color BG_COLOR = new Color(0x18, 0x18, 0x20);
    private static final Color BTN_MODE_COLOR = new Color(0x2E, 0x5B, 0x82);
    private static final Color BTN_COLOR = new Color(0x2B, 0x35, 0x40);
    private static final Color BTN_CLEAR_COLOR = new Color(0x60, 0x2A, 0x2A);
    private static final Color BTN_SAVE_COLOR = new Color(0x1B, 0x4D, 0x3E);
    private static final Color TEXT_COLOR = Color.WHITE;

    private final ScannerState state;
    private final VolumeBuffer volumeBuffer;

    private final JButton btnSwitchMode = createButton("Switch to 2D Mode [TAB]", BTN_MODE_COLOR);
    private final JButton btnRecord = createButton("Pause (Space)", BTN_COLOR);
    private final JButton btnStyle = createButton("Style: 3D Surface [Y]", BTN_COLOR);
    private final JButton btnColormap = createButton("Colormap (V)", BTN_COLOR);
    private final JButton btnSave = createButton("Save 3D Scan (S)", BTN_SAVE_COLOR);
    private final JButton btnOpenFolder = createButton("Scans Folder", BTN_COLOR);
    private final JButton btnClear3D = createButton("Clear 3D Volume", BTN_CLEAR_COLOR);

    private final JComboBox<String> cmbGrid = new JComboBox<>(new String[]{"50 x 50 (Fast)", "64 x 64 (Standard)", "100 x 100 (High-Res)"});
    private final JComboBox<VolumeScanConfig.ScanPattern> cmbPattern = new JComboBox<>(VolumeScanConfig.ScanPattern.values());
    private final JComboBox<Volume3DPanel.CameraPreset> cmbViewPreset = new JComboBox<>(Volume3DPanel.CameraPreset.values());

    private final JSlider sldGateMin = new JSlider(0, 100, 15);
    private final JSlider sldGateMax = new JSlider(10, 120, 60);
    private final JSlider sldExaggeration = new JSlider(5, 40, 18);

    private final JLabel lblGateMin = new JLabel("Gate Min: 15 mm");
    private final JLabel lblGateMax = new JLabel("Gate Max: 60 mm");
    private final JLabel lblExag = new JLabel("Z-Exag: 1.8x");

    public VolumeControlPanel(ScannerState state, VolumeBuffer volumeBuffer) {
        this.state = state;
        this.volumeBuffer = volumeBuffer;

        setLayout(new GridLayout(2, 1, 4, 4));
        setBackground(BG_COLOR);
        setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        ModernUi.styleComboBox(cmbGrid);
        ModernUi.styleComboBox(cmbPattern);
        ModernUi.styleComboBox(cmbViewPreset);

        // Row 1: Primary Action Buttons
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 2));
        row1.setBackground(BG_COLOR);

        btnSwitchMode.setFont(btnSwitchMode.getFont().deriveFont(Font.BOLD));
        btnSwitchMode.setForeground(new Color(0x88, 0xDD, 0xFF));

        btnSave.setFont(btnSave.getFont().deriveFont(Font.BOLD));
        btnSave.setForeground(new Color(0x88, 0xFF, 0xCC));

        row1.add(btnSwitchMode);
        row1.add(btnRecord);
        row1.add(btnStyle);
        row1.add(btnColormap);
        row1.add(btnSave);
        row1.add(btnOpenFolder);
        row1.add(btnClear3D);

        // Row 2: Grid Configuration, Depth Gate Sliders & Z-Exaggeration
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 2));
        row2.setBackground(BG_COLOR);

        JLabel lblGrid = new JLabel("Grid:");
        lblGrid.setForeground(TEXT_COLOR);
        row2.add(lblGrid);
        cmbGrid.setSelectedIndex(0);
        cmbGrid.setFocusable(false);
        row2.add(cmbGrid);

        JLabel lblPat = new JLabel("Pattern:");
        lblPat.setForeground(TEXT_COLOR);
        row2.add(lblPat);
        cmbPattern.setFocusable(false);
        row2.add(cmbPattern);

        JLabel lblView = new JLabel("Camera:");
        lblView.setForeground(TEXT_COLOR);
        row2.add(lblView);
        cmbViewPreset.setFocusable(false);
        row2.add(cmbViewPreset);

        setupSlider(sldGateMin, lblGateMin);
        setupSlider(sldGateMax, lblGateMax);
        setupSlider(sldExaggeration, lblExag);

        row2.add(lblGateMin);
        row2.add(sldGateMin);
        row2.add(lblGateMax);
        row2.add(sldGateMax);
        row2.add(lblExag);
        row2.add(sldExaggeration);

        add(row1);
        add(row2);

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
            int size = (idx == 0) ? 50 : (idx == 1) ? 64 : 100;
            state.getVolumeConfig().setGridX(size);
            state.getVolumeConfig().setGridY(size);
            volumeBuffer.reallocate(state.getVolumeConfig());
        });

        cmbPattern.addActionListener(e -> {
            VolumeScanConfig.ScanPattern p = (VolumeScanConfig.ScanPattern) cmbPattern.getSelectedItem();
            if (p != null) state.getVolumeConfig().setPattern(p);
        });

        sldGateMin.addChangeListener(e -> {
            int minVal = sldGateMin.getValue();
            if (minVal >= sldGateMax.getValue()) {
                sldGateMax.setValue(minVal + 1);
            }
            state.getVolumeConfig().setGateMinDepthMm(minVal);
            lblGateMin.setText(String.format("Gate Min: %d mm", minVal));
        });

        sldGateMax.addChangeListener(e -> {
            int maxVal = sldGateMax.getValue();
            if (maxVal <= sldGateMin.getValue()) {
                sldGateMin.setValue(maxVal - 1);
            }
            state.getVolumeConfig().setGateMaxDepthMm(maxVal);
            lblGateMax.setText(String.format("Gate Max: %d mm", maxVal));
        });

        sldExaggeration.addChangeListener(e -> {
            double exag = sldExaggeration.getValue() / 10.0;
            state.setDepthExaggeration(exag);
            lblExag.setText(String.format("Z-Exag: %.1fx", exag));
        });

        state.addListener(s -> {
            btnSwitchMode.setText(s.getViewMode() == ViewMode.MODE_2D ? "Switch to 3D Mode [TAB]" : "Switch to 2D Mode [TAB]");
            btnRecord.setText(s.isRecording() ? "Pause (Space)" : "Resume (Space)");
            btnStyle.setText("Style: " + s.getRender3DStyle().getLabel() + " [Y]");
            btnColormap.setText("Colormap: " + s.getColormap().getLabel() + " [V]");
        });
    }

    private JButton createButton(String label, Color bg) {
        ModernUi.RoundedButton btn = new ModernUi.RoundedButton(label, bg, ModernUi.lighten(bg, 0.35f), 6);
        btn.setPreferredSize(new Dimension(150, 28));
        btn.setFocusable(false);
        return btn;
    }

    public void onSwitchModeClicked(Runnable r) { btnSwitchMode.addActionListener(e -> r.run()); }
    public void onRecordClicked(Runnable r) { btnRecord.addActionListener(e -> r.run()); }
    public void onStyleClicked(Runnable r) { btnStyle.addActionListener(e -> r.run()); }
    public void onColormapClicked(Runnable r) { btnColormap.addActionListener(e -> r.run()); }
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
}
