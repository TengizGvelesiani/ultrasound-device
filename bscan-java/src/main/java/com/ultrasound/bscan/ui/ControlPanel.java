package com.ultrasound.bscan.ui;

import com.ultrasound.bscan.io.PortInfo;
import com.ultrasound.bscan.model.ScanMode;
import com.ultrasound.bscan.model.ScannerState;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Consumer;

/**
 * Control bar with action buttons, DSP sliders, and hardware connection panel.
 */
public final class ControlPanel extends JPanel {

    private static final Color BG_COLOR = new Color(0x18, 0x18, 0x20);
    private static final Color BTN_COLOR = new Color(0x2B, 0x35, 0x40);
    private static final Color BTN_SAVE_COLOR = new Color(0x1B, 0x4D, 0x3E);
    private static final Color TEXT_COLOR = Color.WHITE;

    private final ScannerState state;

    private final JButton btnRecord = createButton("Pause (Space)", BTN_COLOR);
    private final JButton btnClear = createButton("Clear (C)", BTN_COLOR);
    private final JButton btnMode = createButton("Mode: Sweep (M)", BTN_COLOR);
    private final JButton btnZoom = createButton("Zoom: Full (Z)", BTN_COLOR);
    private final JButton btnColormap = createButton("Colormap (V)", BTN_COLOR);
    private final JButton btnSave = createButton("Save Scan & Data (S)", BTN_SAVE_COLOR);

    private final JSlider sldSquelch = new JSlider(0, 150, (int) ScannerState.DEFAULT_SQUELCH);
    private final JSlider sldContrast = new JSlider(5, 30, (int) (ScannerState.DEFAULT_CONTRAST * 10));
    private final JLabel lblSquelch = new JLabel(String.format("Squelch: %.0f", ScannerState.DEFAULT_SQUELCH));
    private final JLabel lblContrast = new JLabel(String.format("Contrast: %.1fx", ScannerState.DEFAULT_CONTRAST));

    private final JComboBox<PortInfo> cmbPorts = new JComboBox<>();
    private final JButton btnRefreshPorts = createButton("↻ Refresh", BTN_COLOR);
    private final JButton btnConnect = createButton("Connect", new Color(0x20, 0x60, 0x90));
    private final JCheckBox chkSimulation = new JCheckBox("Simulation Mode", false);

    public ControlPanel(ScannerState state) {
        this.state = state;

        setLayout(new GridLayout(3, 1, 4, 4));
        setBackground(BG_COLOR);
        setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        // Row 1: Connection & Hardware Bar
        JPanel connectionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        connectionRow.setBackground(BG_COLOR);
        JLabel lblPort = new JLabel("COM Port:");
        lblPort.setForeground(TEXT_COLOR);
        lblPort.setFont(new Font("SansSerif", Font.BOLD, 11));
        connectionRow.add(lblPort);

        cmbPorts.setPreferredSize(new Dimension(220, 26));
        connectionRow.add(cmbPorts);
        connectionRow.add(btnRefreshPorts);
        connectionRow.add(btnConnect);

        chkSimulation.setForeground(Color.CYAN);
        chkSimulation.setFont(new Font("SansSerif", Font.BOLD, 11));
        chkSimulation.setOpaque(false);
        connectionRow.add(chkSimulation);

        // Row 2: Action Buttons
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 2));
        buttonRow.setBackground(BG_COLOR);
        btnSave.setForeground(new Color(0x88, 0xFF, 0xCC));
        btnSave.setFont(btnSave.getFont().deriveFont(Font.BOLD));

        buttonRow.add(btnRecord);
        buttonRow.add(btnClear);
        buttonRow.add(btnMode);
        buttonRow.add(btnZoom);
        buttonRow.add(btnColormap);
        buttonRow.add(btnSave);

        // Row 3: Sliders (Squelch & Contrast)
        JPanel sliderRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 2));
        sliderRow.setBackground(BG_COLOR);

        setupSlider(sldSquelch, lblSquelch, new Color(0xFF, 0x55, 0x55));
        setupSlider(sldContrast, lblContrast, new Color(0x00, 0xCC, 0xFF));

        sliderRow.add(lblSquelch);
        sliderRow.add(sldSquelch);
        sliderRow.add(lblContrast);
        sliderRow.add(sldContrast);

        add(connectionRow);
        add(buttonRow);
        add(sliderRow);

        setupInternalListeners();
    }

    private void setupSlider(JSlider slider, JLabel label, Color trackColor) {
        slider.setPreferredSize(new Dimension(180, 24));
        slider.setOpaque(false);
        label.setForeground(TEXT_COLOR);
        label.setFont(new Font("SansSerif", Font.PLAIN, 11));
    }

    private void setupInternalListeners() {
        sldSquelch.addChangeListener(e -> {
            state.setSquelch(sldSquelch.getValue());
            lblSquelch.setText(String.format("Squelch: %.0f", state.getSquelch()));
        });

        sldContrast.addChangeListener(e -> {
            state.setContrast(sldContrast.getValue() / 10.0);
            lblContrast.setText(String.format("Contrast: %.1fx", state.getContrast()));
        });

        state.addListener(s -> {
            btnRecord.setText(s.isRecording() ? "Pause (Space)" : "Resume (Space)");
            btnMode.setText("Mode: " + (s.getMode() == ScanMode.SWEEP ? "Sweep (M)" : "Waterfall (M)"));
            btnZoom.setText("Zoom: " + (s.isZoomNear() ? "Near (Z)" : "Full (Z)"));
            btnColormap.setText("Colormap: " + s.getColormap().getLabel() + " (V)");

            if (sldSquelch.getValue() != (int) s.getSquelch()) {
                sldSquelch.setValue((int) s.getSquelch());
                lblSquelch.setText(String.format("Squelch: %.0f", s.getSquelch()));
            }

            int contrastInt = (int) Math.round(s.getContrast() * 10);
            if (sldContrast.getValue() != contrastInt) {
                sldContrast.setValue(contrastInt);
                lblContrast.setText(String.format("Contrast: %.1fx", s.getContrast()));
            }
        });
    }

    private JButton createButton(String label, Color bg) {
        JButton btn = new JButton(label);
        btn.setBackground(bg);
        btn.setForeground(TEXT_COLOR);
        btn.setFocusPainted(false);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        btn.setPreferredSize(new Dimension(140, 28));
        return btn;
    }

    // Listener bindings for Controller
    public void onRecordClicked(Runnable r) { btnRecord.addActionListener(e -> r.run()); }
    public void onClearClicked(Runnable r) { btnClear.addActionListener(e -> r.run()); }
    public void onModeClicked(Runnable r) { btnMode.addActionListener(e -> r.run()); }
    public void onZoomClicked(Runnable r) { btnZoom.addActionListener(e -> r.run()); }
    public void onColormapClicked(Runnable r) { btnColormap.addActionListener(e -> r.run()); }
    public void onSaveClicked(Runnable r) { btnSave.addActionListener(e -> r.run()); }
    public void onRefreshPortsClicked(Runnable r) { btnRefreshPorts.addActionListener(e -> r.run()); }
    public void onConnectClicked(Runnable r) { btnConnect.addActionListener(e -> r.run()); }
    public void onSimulationToggled(Consumer<Boolean> c) { chkSimulation.addActionListener(e -> c.accept(chkSimulation.isSelected())); }

    public void setPorts(List<PortInfo> ports, PortInfo autoSelected) {
        cmbPorts.removeAllItems();
        for (PortInfo p : ports) {
            cmbPorts.addItem(p);
        }
        if (autoSelected != null) {
            cmbPorts.setSelectedItem(autoSelected);
        }
    }

    public PortInfo getSelectedPort() {
        return (PortInfo) cmbPorts.getSelectedItem();
    }

    public void setConnected(boolean connected) {
        btnConnect.setText(connected ? "Disconnect" : "Connect");
        btnConnect.setBackground(connected ? new Color(0x90, 0x30, 0x30) : new Color(0x20, 0x60, 0x90));
        cmbPorts.setEnabled(!connected);
        btnRefreshPorts.setEnabled(!connected);
        chkSimulation.setEnabled(!connected);
    }

    public boolean isSimulationMode() {
        return chkSimulation.isSelected();
    }

    public void setSimulationMode(boolean enabled) {
        chkSimulation.setSelected(enabled);
    }
}
