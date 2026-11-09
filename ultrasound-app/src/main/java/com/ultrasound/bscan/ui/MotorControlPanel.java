package com.ultrasound.bscan.ui;

import com.ultrasound.bscan.controller.MotorControllerService;
import com.ultrasound.bscan.model.LineScanConfig;
import com.ultrasound.bscan.model.MotorSettings;
import javax.swing.JCheckBox;

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
import java.awt.BasicStroke;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

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

    // Mode-Specific Card Containers & Dynamic Layout Gaps
    private JPanel cardLineScan;
    private Component gapLine;
    private JPanel cardChamberScan;
    private Component gapChamber;
    private JPanel cardFeedback;

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

    private final JButton btnJogYPlus = new ModernUi.RoundedButton("Y+ (Sync)", ModernUi.COLOR_SLATE, ModernUi.COLOR_SLATE_LIGHT, 6);
    private final JButton btnJogYMinus = new ModernUi.RoundedButton("Y- (Sync)", ModernUi.COLOR_SLATE, ModernUi.COLOR_SLATE_LIGHT, 6);
    private final JButton btnJogXMinus = new ModernUi.RoundedButton("X-", ModernUi.COLOR_SLATE, ModernUi.COLOR_SLATE_LIGHT, 6);
    private final JButton btnJogXPlus = new ModernUi.RoundedButton("X+", ModernUi.COLOR_SLATE, ModernUi.COLOR_SLATE_LIGHT, 6);
    private final JButton btnHalt = new ModernUi.RoundedButton("HALT", ModernUi.COLOR_RED_DARK, ModernUi.COLOR_RED, 6);

    // Chamber Configuration Spinners (Styled dark inputs)
    private final JSpinner spnChamberX = new JSpinner(new SpinnerNumberModel(10.0, 1.0, 1000.0, 1.0));
    private final JSpinner spnChamberY = new JSpinner(new SpinnerNumberModel(40.0, 1.0, 1000.0, 1.0));
    private final JSpinner spnLineStepY = new JSpinner(new SpinnerNumberModel(1.0, 0.05, 50.0, 0.5));
    private final JSpinner spnLineStepX = new JSpinner(new SpinnerNumberModel(0.10, 0.01, 10.0, 0.05));
    private final JSpinner spnGridPointsX = new JSpinner(new SpinnerNumberModel(100, 2, 5000, 10));
    private final JSpinner spnGridLinesY = new JSpinner(new SpinnerNumberModel(41, 2, 2000, 5));
    private final JSpinner spnStepsPerMmX = new JSpinner(new SpinnerNumberModel(400.0, 10.0, 5000.0, 10.0));
    private final JSpinner spnStepsPerMmY = new JSpinner(new SpinnerNumberModel(400.0, 10.0, 5000.0, 10.0));
    private final JSpinner spnPreviewSpeed = new JSpinner(new SpinnerNumberModel(25.0, 1.0, 100.0, 5.0));
    private final JSpinner spnBacklash = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 5.0, 0.05));

    // Live Grid HUD Banner
    private final JLabel lblGridBanner = new JLabel("● RASTER GRID: 100 × 41  (4,100 pts)");
    private final JLabel lblGridDetails = new JLabel("Res: ΔX=0.10mm, ΔY=1.00mm | Est: ~42s @ 10 mm/s");
    private boolean isUpdatingGrid = false;
    private Runnable onChamberConfigChanged;

    // Chamber Scan Direction Selectors
    private final JComboBox<String> cmbStartXDir = new JComboBox<>(new String[]{
            "+X (Right / Pos)", "-X (Left / Neg)"
    });
    private final JComboBox<String> cmbAdvanceYDir = new JComboBox<>(new String[]{
            "+Y (Forward / Pos)", "-Y (Backward / Neg)"
    });

    // 2D Line Scan Controls
    private final JComboBox<LineScanConfig.ScanAxis> cmbLineAxis = new JComboBox<>(LineScanConfig.ScanAxis.values());
    private final JSpinner spnLineDistance = new JSpinner(new SpinnerNumberModel(10.0, 0.5, 1000.0, 1.0));
    private final JSpinner spnLineStep = new JSpinner(new SpinnerNumberModel(0.10, 0.01, 5.0, 0.05));
    private final JComboBox<String> cmbLineDir = new JComboBox<>(new String[]{
            "+ Positive (Right / Fwd)", "- Negative (Left / Back)"
    });
    private final JCheckBox chkLineAutoReturn = new JCheckBox("Auto-return to start position", true);
    private final JButton btnTriggerLineScan = new ModernUi.RoundedButton("START 2D LINE SCAN", new Color(0x1B, 0x4D, 0x6E), ModernUi.COLOR_CYAN, 6);
    private final JButton btnExportLineScan = new ModernUi.RoundedButton("EXPORT LINE SCAN (CSV & PNG)", new Color(0x1B, 0x4D, 0x3E), ModernUi.COLOR_GREEN, 6);

    // Chamber Area Graphic Preview Canvas
    private final ChamberAreaPreviewCanvas areaCanvas = new ChamberAreaPreviewCanvas();

    // High-End Hero Chamber Scan Trigger Button, Area Preview Button & Progress Bar
    private final JButton btnPreviewScanArea = new ModernUi.RoundedButton("PREVIEW AREA", new Color(0x1B, 0x3A, 0x4F), ACCENT_CYAN, 6);
    private final ModernUi.HeroScanButton btnTriggerChamberScan = new ModernUi.HeroScanButton();
    private final JButton btnAbortScan = new ModernUi.RoundedButton("ABORT", ModernUi.COLOR_RED_DARK, ModernUi.COLOR_RED, 6);
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

        // 4. 2D Line Scan Setup Card (Mode-isolated: visible exclusively in 2D ViewMode)
        cardLineScan = buildLineScanCard();
        gapLine = createVerticalGap(6);
        contentPanel.add(cardLineScan);
        contentPanel.add(gapLine);

        // 5. Chamber Settings & Hero Scan Trigger Card (Mode-isolated: visible exclusively in 3D ViewMode)
        cardChamberScan = buildChamberScanCard();
        gapChamber = createVerticalGap(6);
        contentPanel.add(cardChamberScan);
        contentPanel.add(gapChamber);

        // 6. Scan Execution Monitor & Feedback Card (Common status readout)
        cardFeedback = buildFeedbackCard();
        contentPanel.add(cardFeedback);

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
        ModernUi.styleSpinner(spnLineStepX);
        ModernUi.styleSpinner(spnStepsPerMmX);
        ModernUi.styleSpinner(spnStepsPerMmY);
        ModernUi.styleSpinner(spnPreviewSpeed);
        ModernUi.styleSpinner(spnBacklash);
        ModernUi.styleSpinner(spnGridPointsX);
        ModernUi.styleSpinner(spnGridLinesY);
        btnPreviewScanArea.setToolTipText("Moves the stepper motors quickly along the perimeter rectangle of the scan area and returns to original position.");
        ModernUi.styleComboBox(cmbJogStep);
        ModernUi.styleComboBox(cmbLineAxis);
        ModernUi.styleComboBox(cmbLineDir);
        ModernUi.styleSpinner(spnLineDistance);
        ModernUi.styleSpinner(spnLineStep);

        initEventHandlers();
        motorService.addListener(this);
        updateViewMode(com.ultrasound.bscan.model.ViewMode.MODE_2D);
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

        btnZeroOrigin.setFont(new Font("Segoe UI", Font.BOLD, 9));
        btnZeroOrigin.setPreferredSize(new Dimension(76, 22));

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

    private JPanel buildLineScanCard() {
        JPanel card = createCardPanel("2D LINE SCAN (SINGLE-AXIS)");
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        chkLineAutoReturn.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        chkLineAutoReturn.setForeground(new Color(0xD0, 0xD4, 0xE0));
        chkLineAutoReturn.setOpaque(false);
        chkLineAutoReturn.setFocusable(false);

        JPanel grid = new JPanel(new GridLayout(4, 2, 8, 4)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.setBackground(CARD_BG);

        grid.add(createLabel("Scan Axis:"));
        grid.add(cmbLineAxis);

        grid.add(createLabel("Distance (mm):"));
        grid.add(spnLineDistance);

        grid.add(createLabel("Step \u0394 (mm):"));
        grid.add(spnLineStep);

        grid.add(createLabel("Direction:"));
        grid.add(cmbLineDir);

        card.add(grid);
        card.add(createVerticalGap(6));

        chkLineAutoReturn.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(chkLineAutoReturn);
        card.add(createVerticalGap(6));

        btnTriggerLineScan.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnTriggerLineScan.setPreferredSize(new Dimension(0, 28));
        btnTriggerLineScan.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        btnTriggerLineScan.setFont(new Font("Segoe UI", Font.BOLD, 10));
        card.add(btnTriggerLineScan);
        card.add(createVerticalGap(4));

        btnExportLineScan.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnExportLineScan.setPreferredSize(new Dimension(0, 26));
        btnExportLineScan.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        btnExportLineScan.setFont(new Font("Segoe UI", Font.BOLD, 9));
        btnExportLineScan.setToolTipText("Exports raw ADC matrix, processed matrix, per-line echo diagnostics, and PNG image to scans/ folder");
        card.add(btnExportLineScan);

        return card;
    }

    public void syncLineScanConfig(LineScanConfig lc) {
        if (lc == null) return;
        lc.setAxis((LineScanConfig.ScanAxis) cmbLineAxis.getSelectedItem());
        lc.setDistanceMm(((Number) spnLineDistance.getValue()).doubleValue());
        lc.setStepDeltaMm(((Number) spnLineStep.getValue()).doubleValue());
        lc.setPositiveDir(cmbLineDir.getSelectedIndex() == 0);
        lc.setAutoReturn(chkLineAutoReturn.isSelected());
        lc.setSpeedMmPerSec(sldSpeed.getValue());
    }

    public LineScanConfig getLineScanConfig() {
        LineScanConfig lc = new LineScanConfig();
        syncLineScanConfig(lc);
        return lc;
    }

    public void triggerLineScan() {
        LineScanConfig lc = getLineScanConfig();
        motorService.triggerLineScan(lc);
    }

    public void onExportLineScanClicked(Runnable r) {
        btnExportLineScan.addActionListener(e -> r.run());
    }

    public void updateViewMode(com.ultrasound.bscan.model.ViewMode mode) {
        boolean is2D = mode == com.ultrasound.bscan.model.ViewMode.MODE_2D;
        if (cardLineScan != null) {
            cardLineScan.setVisible(is2D);
        }
        if (gapLine != null) {
            gapLine.setVisible(is2D);
        }
        if (cardChamberScan != null) {
            cardChamberScan.setVisible(!is2D);
        }
        if (gapChamber != null) {
            gapChamber.setVisible(!is2D);
        }
        areaCanvas.repaint();
        revalidate();
        repaint();
    }

    private JPanel buildChamberScanCard() {
        JPanel card = createCardPanel("CHAMBER SCAN SETUP");
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        // Settings grid with dark styled spinners and direction selectors
        ModernUi.styleComboBox(cmbStartXDir);
        ModernUi.styleComboBox(cmbAdvanceYDir);
        cmbStartXDir.setPreferredSize(new Dimension(110, 24));
        cmbAdvanceYDir.setPreferredSize(new Dimension(110, 24));

        JPanel grid = new JPanel(new GridLayout(12, 2, 8, 4)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.setBackground(CARD_BG);

        grid.add(createLabel("Width X (mm):"));
        grid.add(spnChamberX);

        grid.add(createLabel("Grid Points X:"));
        grid.add(spnGridPointsX);

        grid.add(createLabel("Length Y (mm):"));
        grid.add(spnChamberY);

        grid.add(createLabel("Grid Lines Y:"));
        grid.add(spnGridLinesY);

        grid.add(createLabel("Step \u0394X (mm):"));
        grid.add(spnLineStepX);

        grid.add(createLabel("Step \u0394Y (mm):"));
        grid.add(spnLineStepY);

        grid.add(createLabel("Steps/mm X:"));
        grid.add(spnStepsPerMmX);

        grid.add(createLabel("Steps/mm Y:"));
        grid.add(spnStepsPerMmY);

        grid.add(createLabel("Start Dir X:"));
        grid.add(cmbStartXDir);

        grid.add(createLabel("Advance Dir Y:"));
        grid.add(cmbAdvanceYDir);

        grid.add(createLabel("Backlash (mm):"));
        grid.add(spnBacklash);

        grid.add(createLabel("Preview Spd (mm/s):"));
        grid.add(spnPreviewSpeed);

        card.add(grid);
        card.add(createVerticalGap(6));

        // Live Raster Grid HUD Card
        JPanel hudCard = new JPanel();
        hudCard.setLayout(new BoxLayout(hudCard, BoxLayout.Y_AXIS));
        hudCard.setBackground(ModernUi.COLOR_INPUT_BG);
        hudCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ModernUi.COLOR_INPUT_BORDER, 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        hudCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        hudCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));

        lblGridBanner.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lblGridBanner.setForeground(ACCENT_CYAN);
        lblGridBanner.setAlignmentX(Component.LEFT_ALIGNMENT);

        lblGridDetails.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        lblGridDetails.setForeground(TEXT_MUTED);
        lblGridDetails.setAlignmentX(Component.LEFT_ALIGNMENT);

        hudCard.add(lblGridBanner);
        hudCard.add(Box.createVerticalStrut(2));
        hudCard.add(lblGridDetails);

        card.add(hudCard);
        card.add(createVerticalGap(8));

        // FAST PREVIEW SCAN RECTANGLE BUTTON
        btnPreviewScanArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnPreviewScanArea.setPreferredSize(new Dimension(0, 28));
        btnPreviewScanArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        btnPreviewScanArea.setFont(new Font("Segoe UI", Font.BOLD, 10));
        card.add(btnPreviewScanArea);
        card.add(createVerticalGap(6));

        // PROMINENT HERO CHAMBER SCAN TRIGGER BUTTON
        btnTriggerChamberScan.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnTriggerChamberScan.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        btnTriggerChamberScan.setPreferredSize(new Dimension(0, 46));
        card.add(btnTriggerChamberScan);

        return card;
    }

    private JPanel buildFeedbackCard() {
        JPanel card = createCardPanel("SCAN EXECUTION & STATUS");
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        // Graphic footprint canvas
        card.add(areaCanvas);
        card.add(createVerticalGap(6));

        // Abort Button
        btnAbortScan.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnAbortScan.setPreferredSize(new Dimension(0, 26));
        btnAbortScan.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        btnAbortScan.setFont(new Font("Segoe UI", Font.BOLD, 9));
        btnAbortScan.setVisible(false);
        card.add(btnAbortScan);
        card.add(createVerticalGap(4));

        // Sleek Gradient Progress Bar
        prgScanProgress.setAlignmentX(Component.LEFT_ALIGNMENT);
        prgScanProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        prgScanProgress.setString("Ready");
        card.add(prgScanProgress);
        card.add(createVerticalGap(4));

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

        // 2D Line Scan Listeners
        cmbLineAxis.addActionListener(e -> {
            LineScanConfig.ScanAxis a = (LineScanConfig.ScanAxis) cmbLineAxis.getSelectedItem();
            if (a == LineScanConfig.ScanAxis.X) {
                spnLineDistance.setValue(settings.getChamberWidthX());
            } else {
                spnLineDistance.setValue(settings.getChamberLengthY());
            }
            areaCanvas.repaint();
        });
        spnLineDistance.addChangeListener(e -> areaCanvas.repaint());
        cmbLineDir.addActionListener(e -> areaCanvas.repaint());
        btnTriggerLineScan.addActionListener(e -> triggerLineScan());

        // Chamber Configuration Listeners (with bidirectional mm <-> grid resolution sync)
        spnChamberX.addChangeListener(e -> {
            if (isUpdatingGrid) return;
            isUpdatingGrid = true;
            try {
                settings.setChamberWidthX(((Number) spnChamberX.getValue()).doubleValue());
                spnGridPointsX.setValue(settings.getGridPointsX());
                updateGridHud();
                areaCanvas.repaint();
                notifyChamberConfigChanged();
            } finally {
                isUpdatingGrid = false;
            }
        });

        spnChamberY.addChangeListener(e -> {
            if (isUpdatingGrid) return;
            isUpdatingGrid = true;
            try {
                settings.setChamberLengthY(((Number) spnChamberY.getValue()).doubleValue());
                spnGridLinesY.setValue(settings.getGridLinesY());
                updateGridHud();
                areaCanvas.repaint();
                notifyChamberConfigChanged();
            } finally {
                isUpdatingGrid = false;
            }
        });

        spnLineStepX.addChangeListener(e -> {
            if (isUpdatingGrid) return;
            isUpdatingGrid = true;
            try {
                settings.setStepDeltaXMm(((Number) spnLineStepX.getValue()).doubleValue());
                spnGridPointsX.setValue(settings.getGridPointsX());
                updateGridHud();
                areaCanvas.repaint();
                notifyChamberConfigChanged();
            } finally {
                isUpdatingGrid = false;
            }
        });

        spnLineStepY.addChangeListener(e -> {
            if (isUpdatingGrid) return;
            isUpdatingGrid = true;
            try {
                settings.setLineSpacingY(((Number) spnLineStepY.getValue()).doubleValue());
                spnGridLinesY.setValue(settings.getGridLinesY());
                updateGridHud();
                areaCanvas.repaint();
                notifyChamberConfigChanged();
            } finally {
                isUpdatingGrid = false;
            }
        });

        spnGridPointsX.addChangeListener(e -> {
            if (isUpdatingGrid) return;
            isUpdatingGrid = true;
            try {
                int pts = ((Number) spnGridPointsX.getValue()).intValue();
                settings.setGridPointsX(pts);
                spnLineStepX.setValue(settings.getStepDeltaXMm());
                updateGridHud();
                areaCanvas.repaint();
                notifyChamberConfigChanged();
            } finally {
                isUpdatingGrid = false;
            }
        });

        spnGridLinesY.addChangeListener(e -> {
            if (isUpdatingGrid) return;
            isUpdatingGrid = true;
            try {
                int lines = ((Number) spnGridLinesY.getValue()).intValue();
                settings.setGridLinesY(lines);
                spnLineStepY.setValue(settings.getLineSpacingY());
                updateGridHud();
                areaCanvas.repaint();
                notifyChamberConfigChanged();
            } finally {
                isUpdatingGrid = false;
            }
        });
        spnStepsPerMmX.addChangeListener(e -> {
            double val = ((Number) spnStepsPerMmX.getValue()).doubleValue();
            settings.setStepsPerMmX(val);
            long stepHz = Math.round(settings.getSpeedMmPerSec() * val);
            lblSpeedValue.setText(String.format("%.1f mm/s (%d Hz)", settings.getSpeedMmPerSec(), stepHz));
            motorService.updateSpeed();
            areaCanvas.repaint();
        });
        spnStepsPerMmY.addChangeListener(e -> {
            double val = ((Number) spnStepsPerMmY.getValue()).doubleValue();
            settings.setStepsPerMmY(val);
            motorService.updateSpeed();
            areaCanvas.repaint();
        });
        spnPreviewSpeed.addChangeListener(e -> {
            settings.setPreviewSpeedMmPerSec(((Number) spnPreviewSpeed.getValue()).doubleValue());
        });

        spnBacklash.addChangeListener(e -> {
            settings.setBacklashCompensationMm(((Number) spnBacklash.getValue()).doubleValue());
        });

        // Direction Selectors Listeners
        cmbStartXDir.addActionListener(e -> {
            settings.setStartPositiveX(cmbStartXDir.getSelectedIndex() == 0);
            areaCanvas.repaint();
        });
        cmbAdvanceYDir.addActionListener(e -> {
            settings.setAdvancePositiveY(cmbAdvanceYDir.getSelectedIndex() == 0);
            areaCanvas.repaint();
        });

        // PREVIEW SCAN AREA ACTION
        btnPreviewScanArea.addActionListener(e -> {
            settings.setChamberWidthX(((Number) spnChamberX.getValue()).doubleValue());
            settings.setChamberLengthY(((Number) spnChamberY.getValue()).doubleValue());
            settings.setLineSpacingY(((Number) spnLineStepY.getValue()).doubleValue());
            settings.setStepDeltaXMm(((Number) spnLineStepX.getValue()).doubleValue());
            settings.setStepsPerMmX(((Number) spnStepsPerMmX.getValue()).doubleValue());
            settings.setStepsPerMmY(((Number) spnStepsPerMmY.getValue()).doubleValue());
            settings.setStartPositiveX(cmbStartXDir.getSelectedIndex() == 0);
            settings.setAdvancePositiveY(cmbAdvanceYDir.getSelectedIndex() == 0);
            settings.setPreviewSpeedMmPerSec(((Number) spnPreviewSpeed.getValue()).doubleValue());

            motorService.previewScanArea();
        });

        // ONE-CLICK CHAMBER SCAN TRIGGER
        btnTriggerChamberScan.addActionListener(e -> {
            // Apply current parameters
            settings.setChamberWidthX(((Number) spnChamberX.getValue()).doubleValue());
            settings.setChamberLengthY(((Number) spnChamberY.getValue()).doubleValue());
            settings.setLineSpacingY(((Number) spnLineStepY.getValue()).doubleValue());
            settings.setStepDeltaXMm(((Number) spnLineStepX.getValue()).doubleValue());
            settings.setStepsPerMmX(((Number) spnStepsPerMmX.getValue()).doubleValue());
            settings.setStepsPerMmY(((Number) spnStepsPerMmY.getValue()).doubleValue());
            settings.setSpeedMmPerSec(sldSpeed.getValue());
            settings.setBacklashCompensationMm(((Number) spnBacklash.getValue()).doubleValue());
            settings.setStartPositiveX(cmbStartXDir.getSelectedIndex() == 0);
            settings.setAdvancePositiveY(cmbAdvanceYDir.getSelectedIndex() == 0);

            motorService.triggerChamberScan();
        });
    }

    private void updateGridHud() {
        int gx = settings.getGridPointsX();
        int gy = settings.getGridLinesY();
        int totalPts = settings.getTotalScanPoints();
        double estSec = settings.getEstimatedScanTimeSec();
        String timeStr = estSec >= 60.0
                ? String.format("%dm %02ds", (int) (estSec / 60), (int) (estSec % 60))
                : String.format("%.0fs", estSec);

        lblGridBanner.setText(String.format("● RASTER GRID: %d × %d  (%,d pts)", gx, gy, totalPts));
        lblGridDetails.setText(String.format("Res: ΔX=%.2fmm, ΔY=%.2fmm | Est: ~%s @ %.0f mm/s",
                settings.getStepDeltaXMm(), settings.getLineSpacingY(), timeStr, settings.getSpeedMmPerSec()));
    }

    public void applyDensityPreset(double deltaXMm, double deltaYMm) {
        if (isUpdatingGrid) return;
        isUpdatingGrid = true;
        try {
            settings.setStepDeltaXMm(deltaXMm);
            settings.setLineSpacingY(deltaYMm);
            spnLineStepX.setValue(settings.getStepDeltaXMm());
            spnLineStepY.setValue(settings.getLineSpacingY());
            spnGridPointsX.setValue(settings.getGridPointsX());
            spnGridLinesY.setValue(settings.getGridLinesY());
            updateGridHud();
            areaCanvas.repaint();
            notifyChamberConfigChanged();
        } finally {
            isUpdatingGrid = false;
        }
    }

    public void setOnChamberConfigChanged(Runnable r) {
        this.onChamberConfigChanged = r;
    }

    private void notifyChamberConfigChanged() {
        if (onChamberConfigChanged != null) {
            onChamberConfigChanged.run();
        }
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
            areaCanvas.repaint();
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
            btnTriggerLineScan.setEnabled(!isScanning);
            btnTriggerChamberScan.setEnabled(!isScanning);
            btnPreviewScanArea.setEnabled(!isScanning);
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

    /**
     * Compact graphical canvas visualizing the chamber scan area footprint,
     * configured sweep directions, and live stepper motor position.
     */
    private final class ChamberAreaPreviewCanvas extends JPanel {
        public ChamberAreaPreviewCanvas() {
            setOpaque(false);
            setPreferredSize(new Dimension(0, 95));
            setMinimumSize(new Dimension(150, 80));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 105));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // Background card
            g2.setColor(ModernUi.COLOR_INPUT_BG);
            g2.fillRoundRect(0, 0, w, h, 8, 8);
            g2.setColor(ModernUi.COLOR_INPUT_BORDER);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

            boolean is2DMode = cardLineScan != null && cardLineScan.isVisible();
            boolean isLineScan = motorService.isLineScanning();
            boolean isPreview = motorService.isPreviewRunning();

            int padX = 28;
            int padY = 20;
            int availW = w - padX * 2;
            int availH = h - padY * 2;

            if (availW <= 10 || availH <= 10) {
                g2.dispose();
                return;
            }

            if (is2DMode) {
                LineScanConfig.ScanAxis axis = (LineScanConfig.ScanAxis) cmbLineAxis.getSelectedItem();
                boolean posDir = cmbLineDir.getSelectedIndex() == 0;
                double dist = ((Number) spnLineDistance.getValue()).doubleValue();

                g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                g2.setColor(TEXT_MUTED);
                String infoStr = String.format("2D Line: %s-Axis (%.1f mm)", axis != null ? axis.name() : "X", dist);
                g2.drawString(infoStr, 8, 13);

                String dirStr = posDir ? "Sweep: Positive (+)" : "Sweep: Negative (-)";
                int dirW = g2.getFontMetrics().stringWidth(dirStr);
                g2.drawString(dirStr, w - dirW - 8, 13);

                int pad = 30;
                if (axis == LineScanConfig.ScanAxis.X) {
                    int lineY = h / 2 - 2;
                    int x1 = pad;
                    int x2 = w - pad;
                    int startX = posDir ? x1 : x2;
                    int endX = posDir ? x2 : x1;

                    // Guide rail
                    g2.setColor(new Color(0x24, 0x2B, 0x3A));
                    g2.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(x1, lineY, x2, lineY);

                    // Active trajectory
                    g2.setColor(isLineScan ? ACCENT_CYAN : new Color(0x00, 0x97, 0xA7));
                    g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(startX, lineY, endX, lineY);

                    // Start origin dot
                    g2.setColor(ACCENT_GREEN);
                    g2.fillOval(startX - 4, lineY - 4, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.drawOval(startX - 4, lineY - 4, 8, 8);

                    // Direction arrow
                    drawSmallArrow(g2, startX, lineY, (startX + endX) / 2, lineY, ACCENT_CYAN);
                } else {
                    int lineX = w / 2;
                    int y1 = h - 18;
                    int y2 = 18;
                    int startY = posDir ? y1 : y2;
                    int endY = posDir ? y2 : y1;

                    // Guide rail
                    g2.setColor(new Color(0x24, 0x2B, 0x3A));
                    g2.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(lineX, y2, lineX, y1);

                    // Active trajectory
                    g2.setColor(isLineScan ? ACCENT_CYAN : new Color(0x00, 0x97, 0xA7));
                    g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(lineX, startY, lineX, endY);

                    // Start origin dot
                    g2.setColor(ACCENT_GREEN);
                    g2.fillOval(lineX - 4, startY - 4, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.drawOval(lineX - 4, startY - 4, 8, 8);

                    // Direction arrow
                    drawSmallArrow(g2, lineX, startY, lineX, (startY + endY) / 2, ACCENT_CYAN);
                }

                if (isLineScan) {
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                    g2.setColor(ACCENT_CYAN);
                    String pStr = "● 2D LINE SCAN IN PROGRESS";
                    int pW = g2.getFontMetrics().stringWidth(pStr);
                    g2.drawString(pStr, (w - pW) / 2, h - 4);
                }
            } else {
                double widthX = settings.getChamberWidthX();
                double lengthY = settings.getChamberLengthY();
                boolean startPosX = settings.isStartPositiveX();
                boolean advancePosY = settings.isAdvancePositiveY();

                // Aspect ratio scaling
                double aspect = widthX / Math.max(1.0, lengthY);
                double rectW = availW;
                double rectH = availW / aspect;
                if (rectH > availH) {
                    rectH = availH;
                    rectW = availH * aspect;
                }
                rectW = Math.max(24, Math.min(availW, rectW));
                rectH = Math.max(24, Math.min(availH, rectH));

                int rx = (int) Math.round((w - rectW) / 2.0);
                int ry = (int) Math.round((h - rectH) / 2.0) + 2;

                // Fill area
                Color fillColor = isPreview ? new Color(0x00, 0xE5, 0xFF, 40) : new Color(0x00, 0xE5, 0xFF, 18);
                g2.setColor(fillColor);
                g2.fillRect(rx, ry, (int) rectW, (int) rectH);

                // Subtle hatched raster lines inside to represent scan lines
                g2.setColor(new Color(0x00, 0xE5, 0xFF, 30));
                int numLines = Math.min(8, Math.max(3, (int) (rectH / 8)));
                for (int i = 1; i < numLines; i++) {
                    int ly = ry + (int) (i * rectH / numLines);
                    g2.drawLine(rx + 2, ly, rx + (int) rectW - 2, ly);
                }

                // Perimeter outline
                g2.setColor(isPreview ? ACCENT_CYAN : ModernUi.COLOR_CYAN_DIM);
                g2.setStroke(new BasicStroke(isPreview ? 2.0f : 1.2f));
                g2.drawRect(rx, ry, (int) rectW, (int) rectH);

                // Starting corner indicator
                int startCornerX = startPosX ? rx : (rx + (int) rectW);
                int startCornerY = advancePosY ? (ry + (int) rectH) : ry;

                g2.setColor(ACCENT_GREEN);
                g2.fillOval(startCornerX - 4, startCornerY - 4, 8, 8);
                g2.setColor(Color.WHITE);
                g2.drawOval(startCornerX - 4, startCornerY - 4, 8, 8);

                // Direction arrow along Leg 1 (sweep X)
                int arrowY = startCornerY;
                int arrowX1 = startPosX ? (rx + 4) : (rx + (int) rectW - 4);
                int arrowX2 = startPosX ? (rx + Math.min(26, (int) rectW - 4)) : (rx + (int) rectW - Math.min(26, (int) rectW - 4));
                drawSmallArrow(g2, arrowX1, arrowY, arrowX2, arrowY, ACCENT_CYAN);

                // Text Labels
                g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                g2.setColor(TEXT_MUTED);
                String dimStr = String.format("%.1f × %.1f mm  (Grid: %d × %d)",
                        widthX, lengthY, settings.getGridPointsX(), settings.getGridLinesY());
                g2.drawString(dimStr, 8, 13);

                String dirStr = String.format("Start: %s, %s", startPosX ? "+X" : "-X", advancePosY ? "+Y" : "-Y");
                int dirW = g2.getFontMetrics().stringWidth(dirStr);
                g2.drawString(dirStr, w - dirW - 8, 13);

                if (isPreview) {
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                    g2.setColor(ACCENT_CYAN);
                    String pStr = "● TRACING SCAN RECTANGLE";
                    int pW = g2.getFontMetrics().stringWidth(pStr);
                    g2.drawString(pStr, (w - pW) / 2, h - 4);
                }
            }

            g2.dispose();
        }

        private void drawSmallArrow(Graphics2D g2, int x1, int y1, int x2, int y2, Color color) {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(x1, y1, x2, y2);
            double angle = Math.atan2(y2 - y1, x2 - x1);
            int arrowLen = 5;
            int x3 = (int) (x2 - arrowLen * Math.cos(angle - Math.PI / 6));
            int y3 = (int) (y2 - arrowLen * Math.sin(angle - Math.PI / 6));
            int x4 = (int) (x2 - arrowLen * Math.cos(angle + Math.PI / 6));
            int y4 = (int) (y2 - arrowLen * Math.sin(angle + Math.PI / 6));
            g2.drawLine(x2, y2, x3, y3);
            g2.drawLine(x2, y2, x4, y4);
        }
    }
}
