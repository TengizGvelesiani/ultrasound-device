package com.ultrasound.bscan.ui;

import com.ultrasound.bscan.controller.MotorControllerService;
import com.ultrasound.bscan.model.MotorSettings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;

/**
 * Dedicated sidebar control panel for motorized ultrasound stage control and full chamber scans.
 * Redesigned with modern industrial instrumentation aesthetics, high-contrast dark theme,
 * vector icon hero trigger button, and digital gauge readouts.
 */
public final class MotorControlPanel extends JPanel implements MotorControllerService.MotorStateListener {

    private static final Color BG_PANEL = ModernUi.COLOR_BG_PANEL;
    private static final Color CARD_BG = ModernUi.COLOR_CARD_BG;
    private static final Color CARD_BORDER = ModernUi.COLOR_CARD_BORDER;
    private static final Color ACCENT_CYAN = ModernUi.COLOR_CYAN;
    private static final Color ACCENT_GREEN = ModernUi.COLOR_GREEN;
    private static final Color ACCENT_RED = ModernUi.COLOR_RED;
    private static final Color TEXT_MUTED = new Color(0x9E, 0xA2, 0xB4);

    private final MotorControllerService motorService;
    private final MotorSettings settings;

    // Digital Coordinate Readouts
    private final JLabel lblPositionX = new JLabel("+0.00 mm");
    private final JLabel lblPositionY = new JLabel("+0.00 mm");
    private final JLabel lblStepsX = new JLabel("0 microsteps");
    private final JLabel lblStepsY = new JLabel("0 microsteps");
    private final JButton btnZeroOrigin = new ModernUi.RoundedButton("SET ZERO", new Color(0x2C, 0x33, 0x46), ACCENT_CYAN, 6);

    // Jog Controls
    private final JComboBox<String> cmbJogStep = new JComboBox<>(new String[]{
            "0.5 mm", "1.0 mm", "5.0 mm", "10.0 mm", "25.0 mm", "50.0 mm"
    });
    private final JSlider sldSpeed = new JSlider(1, 30, 10);
    private final JLabel lblSpeedValue = new JLabel("10.0 mm/s (4000 Hz)");

    private final JButton btnJogYPlus = new ModernUi.RoundedButton("▲  Y+ (Bottom In Sync)", ModernUi.COLOR_SLATE, ModernUi.COLOR_SLATE_LIGHT, 6);
    private final JButton btnJogYMinus = new ModernUi.RoundedButton("▼  Y- (Bottom In Sync)", ModernUi.COLOR_SLATE, ModernUi.COLOR_SLATE_LIGHT, 6);
    private final JButton btnJogXMinus = new ModernUi.RoundedButton("<  X-", ModernUi.COLOR_SLATE, ModernUi.COLOR_SLATE_LIGHT, 6);
    private final JButton btnJogXPlus = new ModernUi.RoundedButton("X+  >", ModernUi.COLOR_SLATE, ModernUi.COLOR_SLATE_LIGHT, 6);
    private final JButton btnHalt = new ModernUi.RoundedButton("■  HALT", ModernUi.COLOR_RED_DARK, ModernUi.COLOR_RED, 6);

    // Chamber Configuration Spinners (Styled dark inputs)
    private final JSpinner spnChamberX = new JSpinner(new SpinnerNumberModel(100.0, 5.0, 1000.0, 5.0));
    private final JSpinner spnChamberY = new JSpinner(new SpinnerNumberModel(100.0, 5.0, 1000.0, 5.0));
    private final JSpinner spnLineStepY = new JSpinner(new SpinnerNumberModel(1.0, 0.1, 20.0, 0.5));
    private final JSpinner spnStepsPerMm = new JSpinner(new SpinnerNumberModel(400.0, 10.0, 5000.0, 10.0));

    // High-End Hero Chamber Scan Trigger Button & Progress Bar
    private final ModernUi.HeroScanButton btnTriggerChamberScan = new ModernUi.HeroScanButton();
    private final JButton btnAbortScan = new ModernUi.RoundedButton("■  ABORT SCAN", ModernUi.COLOR_RED_DARK, ModernUi.COLOR_RED, 6);
    private final ModernUi.ModernProgressBar prgScanProgress = new ModernUi.ModernProgressBar(new Color(0x00, 0xC8, 0x53), new Color(0x00, 0xE5, 0xFF));
    private final JLabel lblScanStatus = new JLabel("● Gantry ready");

    /**
     * Viewport-width-tracking panel preventing any horizontal clipping or cutoff.
     */
    private static final class ScrollableContentPanel extends JPanel implements Scrollable {
        public ScrollableContentPanel() {
            super();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    public MotorControlPanel(MotorControllerService motorService) {
        this.motorService = motorService;
        this.settings = motorService.getSettings();

        setLayout(new BorderLayout());
        setBackground(BG_PANEL);
        setPreferredSize(new Dimension(370, 750));
        setMinimumSize(new Dimension(320, 350));
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, CARD_BORDER));

        ScrollableContentPanel contentPanel = new ScrollableContentPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(BG_PANEL);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // 1. Header
        contentPanel.add(buildHeaderSection());
        contentPanel.add(createVerticalGap(6));

        // 2. Digital Coordinates HUD Card
        contentPanel.add(buildPositionCard());
        contentPanel.add(createVerticalGap(6));

        // 3. Stepper Jogging Keypad & Speed Card
        contentPanel.add(buildJogControlCard());
        contentPanel.add(createVerticalGap(6));

        // 4. Chamber Settings & Hero Scan Trigger Card
        contentPanel.add(buildChamberScanCard());

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);
        scrollPane.setBackground(BG_PANEL);
        scrollPane.getViewport().setBackground(BG_PANEL);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        add(scrollPane, BorderLayout.CENTER);

        // Apply dark styling to inputs
        ModernUi.styleSpinner(spnChamberX);
        ModernUi.styleSpinner(spnChamberY);
        ModernUi.styleSpinner(spnLineStepY);
        ModernUi.styleSpinner(spnStepsPerMm);
        ModernUi.styleComboBox(cmbJogStep);

        initEventHandlers();
        motorService.addListener(this);
    }

    private JPanel buildHeaderSection() {
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBackground(BG_PANEL);

        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleRow.setBackground(BG_PANEL);

        JLabel dot = new JLabel("●");
        dot.setFont(new Font("Segoe UI", Font.BOLD, 12));
        dot.setForeground(ACCENT_CYAN);

        JLabel title = new JLabel("STAGE & MOTOR CONTROL");
        title.setFont(new Font("Segoe UI", Font.BOLD, 12));
        title.setForeground(Color.WHITE);

        titleRow.add(dot);
        titleRow.add(title);

        JLabel sub = new JLabel("  Dual Y-Axis Locked • 1/16 Microstepping");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        sub.setForeground(TEXT_MUTED);

        panel.add(titleRow, BorderLayout.NORTH);
        panel.add(sub, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildPositionCard() {
        JPanel card = createCardPanel(null);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JPanel headerRow = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerRow.setBackground(CARD_BG);

        JLabel title = new JLabel("STAGE COORDINATES");
        title.setFont(new Font("Segoe UI", Font.BOLD, 11));
        title.setForeground(ACCENT_CYAN);

        btnZeroOrigin.setFont(new Font("Segoe UI", Font.BOLD, 10));
        btnZeroOrigin.setPreferredSize(new Dimension(84, 22));

        headerRow.add(title, BorderLayout.WEST);
        headerRow.add(btnZeroOrigin, BorderLayout.EAST);
        card.add(headerRow);
        card.add(createVerticalGap(6));

        // Digital HUD recessed readout box
        JPanel hudBox = new JPanel(new GridLayout(1, 2, 8, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        hudBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        hudBox.setBackground(new Color(0x0F, 0x11, 0x18));
        hudBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x2B, 0x30, 0x42), 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));

        // X Column
        JPanel colX = new JPanel();
        colX.setLayout(new BoxLayout(colX, BoxLayout.Y_AXIS));
        colX.setBackground(new Color(0x0F, 0x11, 0x18));

        JLabel tagX = new JLabel("X AXIS");
        tagX.setFont(new Font("Segoe UI", Font.BOLD, 9));
        tagX.setForeground(new Color(0x80, 0xDE, 0xEA));

        lblPositionX.setFont(new Font("Consolas", Font.BOLD, 15));
        lblPositionX.setForeground(Color.WHITE);

        lblStepsX.setFont(new Font("Consolas", Font.PLAIN, 9));
        lblStepsX.setForeground(new Color(0x75, 0x82, 0x96));

        colX.add(tagX);
        colX.add(lblPositionX);
        colX.add(lblStepsX);

        // Y Column
        JPanel colY = new JPanel();
        colY.setLayout(new BoxLayout(colY, BoxLayout.Y_AXIS));
        colY.setBackground(new Color(0x0F, 0x11, 0x18));

        JLabel tagY = new JLabel("Y AXIS (DUAL)");
        tagY.setFont(new Font("Segoe UI", Font.BOLD, 9));
        tagY.setForeground(new Color(0x80, 0xDE, 0xEA));

        lblPositionY.setFont(new Font("Consolas", Font.BOLD, 15));
        lblPositionY.setForeground(Color.WHITE);

        lblStepsY.setFont(new Font("Consolas", Font.PLAIN, 9));
        lblStepsY.setForeground(new Color(0x75, 0x82, 0x96));

        colY.add(tagY);
        colY.add(lblPositionY);
        colY.add(lblStepsY);

        hudBox.add(colX);
        hudBox.add(colY);

        card.add(hudBox);
        return card;
    }

    private JPanel buildJogControlCard() {
        JPanel card = createCardPanel("MANUAL GANTRY");
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        // Step increment selector
        JPanel rowStep = new JPanel(new BorderLayout(6, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        rowStep.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowStep.setBackground(CARD_BG);
        JLabel lblStep = new JLabel("Jog Step Increment:");
        lblStep.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblStep.setForeground(TEXT_MUTED);

        cmbJogStep.setSelectedIndex(1); // 1.0 mm default
        cmbJogStep.setPreferredSize(new Dimension(110, 24));

        rowStep.add(lblStep, BorderLayout.WEST);
        rowStep.add(cmbJogStep, BorderLayout.EAST);
        card.add(rowStep);
        card.add(createVerticalGap(6));

        // Speed Slider
        JPanel rowSpeed = new JPanel(new BorderLayout(4, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        rowSpeed.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowSpeed.setBackground(CARD_BG);
        JLabel lblSpeed = new JLabel("Jog Speed Rate:");
        lblSpeed.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblSpeed.setForeground(TEXT_MUTED);

        lblSpeedValue.setFont(new Font("Consolas", Font.BOLD, 10));
        lblSpeedValue.setForeground(ACCENT_CYAN);

        rowSpeed.add(lblSpeed, BorderLayout.WEST);
        rowSpeed.add(lblSpeedValue, BorderLayout.EAST);
        card.add(rowSpeed);

        sldSpeed.setAlignmentX(Component.LEFT_ALIGNMENT);
        sldSpeed.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        sldSpeed.setBackground(CARD_BG);
        sldSpeed.setForeground(ACCENT_CYAN);
        sldSpeed.setFocusable(false);
        card.add(sldSpeed);
        card.add(createVerticalGap(6));

        // Direction Keypad
        JPanel keypad = new JPanel(new GridBagLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        keypad.setAlignmentX(Component.LEFT_ALIGNMENT);
        keypad.setBackground(CARD_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.BOTH;

        // Top: Y+
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 3;
        btnJogYPlus.setPreferredSize(new Dimension(0, 28));
        btnJogYPlus.setToolTipText("Moves both bottom Y1 and Y2 steppers forward in identical lockstep");
        keypad.add(btnJogYPlus, gbc);

        // Middle Row: X-, HALT, X+
        gbc.gridy = 1;
        gbc.gridwidth = 1;

        gbc.gridx = 0;
        btnJogXMinus.setPreferredSize(new Dimension(85, 30));
        keypad.add(btnJogXMinus, gbc);

        gbc.gridx = 1;
        btnHalt.setPreferredSize(new Dimension(85, 30));
        keypad.add(btnHalt, gbc);

        gbc.gridx = 2;
        btnJogXPlus.setPreferredSize(new Dimension(85, 30));
        keypad.add(btnJogXPlus, gbc);

        // Bottom: Y-
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 3;
        btnJogYMinus.setPreferredSize(new Dimension(0, 28));
        btnJogYMinus.setToolTipText("Moves both bottom Y1 and Y2 steppers backward in identical lockstep");
        keypad.add(btnJogYMinus, gbc);

        card.add(keypad);

        JLabel lblSyncNotice = new JLabel("Bottom steppers (Y1 + Y2) strictly locked in unison", SwingConstants.CENTER);
        lblSyncNotice.setFont(new Font("Segoe UI", Font.ITALIC, 9));
        lblSyncNotice.setForeground(new Color(0x75, 0x82, 0x94));
        lblSyncNotice.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblSyncNotice.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
        card.add(createVerticalGap(4));
        card.add(lblSyncNotice);

        return card;
    }

    private JPanel buildChamberScanCard() {
        JPanel card = createCardPanel("CHAMBER SCAN SETUP");
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        // Settings grid with dark styled spinners
        JPanel grid = new JPanel(new GridLayout(4, 2, 8, 4)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.setBackground(CARD_BG);

        grid.add(createLabel("Width X (mm):"));
        grid.add(spnChamberX);

        grid.add(createLabel("Length Y (mm):"));
        grid.add(spnChamberY);

        grid.add(createLabel("Step \u0394Y (mm):"));
        grid.add(spnLineStepY);

        grid.add(createLabel("Steps/mm (1/16):"));
        grid.add(spnStepsPerMm);

        card.add(grid);
        card.add(createVerticalGap(10));

        // PROMINENT HERO CHAMBER SCAN TRIGGER BUTTON
        btnTriggerChamberScan.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnTriggerChamberScan.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        card.add(btnTriggerChamberScan);
        card.add(createVerticalGap(6));

        // Abort Button
        btnAbortScan.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnAbortScan.setPreferredSize(new Dimension(0, 28));
        btnAbortScan.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        btnAbortScan.setVisible(false);
        card.add(btnAbortScan);
        card.add(createVerticalGap(4));

        // Sleek Gradient Progress Bar
        prgScanProgress.setAlignmentX(Component.LEFT_ALIGNMENT);
        prgScanProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        prgScanProgress.setString("Ready");
        card.add(prgScanProgress);
        card.add(createVerticalGap(6));

        lblScanStatus.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblScanStatus.setForeground(TEXT_MUTED);
        lblScanStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblScanStatus.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        card.add(lblScanStatus);

        return card;
    }

    private void initEventHandlers() {
        // Zero position
        btnZeroOrigin.addActionListener(e -> motorService.zeroPosition());

        // Halt / Stop
        btnHalt.addActionListener(e -> motorService.halt());
        btnAbortScan.addActionListener(e -> motorService.halt());

        // Jog Buttons
        btnJogXMinus.addActionListener(e -> motorService.jogX(false, getSelectedJogIncrementMm()));
        btnJogXPlus.addActionListener(e -> motorService.jogX(true, getSelectedJogIncrementMm()));
        btnJogYPlus.addActionListener(e -> motorService.jogY(true, getSelectedJogIncrementMm()));
        btnJogYMinus.addActionListener(e -> motorService.jogY(false, getSelectedJogIncrementMm()));

        // Jog Step Combo
        cmbJogStep.addActionListener(e -> {
            settings.setJogIncrementMm(getSelectedJogIncrementMm());
        });

        // Speed Slider
        sldSpeed.addChangeListener(e -> {
            double speed = sldSpeed.getValue();
            settings.setSpeedMmPerSec(speed);
            long stepHz = Math.round(speed * settings.getStepsPerMmX());
            lblSpeedValue.setText(String.format("%.1f mm/s (%d Hz)", speed, stepHz));
            motorService.updateSpeed();
        });

        // Chamber Configuration Listeners
        spnChamberX.addChangeListener(e -> settings.setChamberWidthX(((Number) spnChamberX.getValue()).doubleValue()));
        spnChamberY.addChangeListener(e -> settings.setChamberLengthY(((Number) spnChamberY.getValue()).doubleValue()));
        spnLineStepY.addChangeListener(e -> settings.setLineSpacingY(((Number) spnLineStepY.getValue()).doubleValue()));
        spnStepsPerMm.addChangeListener(e -> {
            double val = ((Number) spnStepsPerMm.getValue()).doubleValue();
            settings.setStepsPerMmX(val);
            settings.setStepsPerMmY(val);
            long stepHz = Math.round(settings.getSpeedMmPerSec() * val);
            lblSpeedValue.setText(String.format("%.1f mm/s (%d Hz)", settings.getSpeedMmPerSec(), stepHz));
            motorService.updateSpeed();
        });

        // ONE-CLICK CHAMBER SCAN TRIGGER
        btnTriggerChamberScan.addActionListener(e -> {
            // Apply current parameters
            settings.setChamberWidthX(((Number) spnChamberX.getValue()).doubleValue());
            settings.setChamberLengthY(((Number) spnChamberY.getValue()).doubleValue());
            settings.setLineSpacingY(((Number) spnLineStepY.getValue()).doubleValue());
            settings.setStepsPerMmX(((Number) spnStepsPerMm.getValue()).doubleValue());
            settings.setStepsPerMmY(((Number) spnStepsPerMm.getValue()).doubleValue());
            settings.setSpeedMmPerSec(sldSpeed.getValue());

            motorService.triggerChamberScan();
        });
    }

    private double getSelectedJogIncrementMm() {
        String item = (String) cmbJogStep.getSelectedItem();
        if (item == null) return 1.0;
        try {
            return Double.parseDouble(item.replace("mm", "").trim());
        } catch (Exception ex) {
            return 1.0;
        }
    }

    @Override
    public void onPositionUpdated(double xMm, double yMm, long xSteps, long ySteps) {
        SwingUtilities.invokeLater(() -> {
            lblPositionX.setText(String.format("%+7.2f mm", xMm));
            lblPositionY.setText(String.format("%+7.2f mm", yMm));
            lblStepsX.setText(String.format("%,d steps", xSteps));
            lblStepsY.setText(String.format("%,d steps", ySteps));
        });
    }

    @Override
    public void onScanProgress(int currentLine, int totalLines, double percent) {
        SwingUtilities.invokeLater(() -> {
            int p = (int) Math.clamp(Math.round(percent), 0, 100);
            prgScanProgress.setValue(p);
            prgScanProgress.setString(String.format("Scanning: %d%% (Line %d / %d)", p, currentLine, totalLines));
        });
    }

    @Override
    public void onScanStateChanged(boolean isScanning, String message) {
        SwingUtilities.invokeLater(() -> {
            btnTriggerChamberScan.setEnabled(!isScanning);
            btnAbortScan.setVisible(isScanning);
            lblScanStatus.setText(isScanning ? "● " + message : "● " + (message != null ? message : "Gantry ready"));
            lblScanStatus.setForeground(isScanning ? ACCENT_CYAN : (message != null && message.contains("Completed") ? ACCENT_GREEN : TEXT_MUTED));

            // Disable manual jog during autonomous scan
            btnJogXMinus.setEnabled(!isScanning);
            btnJogXPlus.setEnabled(!isScanning);
            btnJogYPlus.setEnabled(!isScanning);
            btnJogYMinus.setEnabled(!isScanning);
            btnZeroOrigin.setEnabled(!isScanning);

            if (!isScanning) {
                prgScanProgress.setString(prgScanProgress.getValue() >= 100 ? "Complete (100%)" : "Ready");
            }
        });
    }

    private Component createVerticalGap(int height) {
        Component strut = Box.createVerticalStrut(height);
        if (strut instanceof JComponent jc) {
            jc.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        return strut;
    }

    private JPanel createCardPanel(String title) {
        JPanel panel = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                Dimension pref = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, pref.height);
            }
        };
        panel.setBackground(CARD_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CARD_BORDER, 1, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (title != null && !title.isEmpty()) {
            JLabel lbl = new JLabel(title);
            lbl.setFont(new Font("Segoe UI", Font.BOLD, 10));
            lbl.setForeground(ACCENT_CYAN);
            lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(lbl);
            panel.add(createVerticalGap(6));
        }
        return panel;
    }

    private JLabel createLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        label.setForeground(TEXT_MUTED);
        return label;
    }
}
