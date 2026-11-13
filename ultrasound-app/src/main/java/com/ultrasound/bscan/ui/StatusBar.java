package com.ultrasound.bscan.ui;

import com.ultrasound.bscan.model.ScannerState;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

/**
 * Bottom status bar displaying system notifications, PRF, and frame statistics.
 */
public final class StatusBar extends JPanel {

    private static final Color BG_COLOR = new Color(0x10, 0x10, 0x14);
    private static final Color MSG_COLOR = new Color(0x00, 0xFF, 0xAA);
    private static final Color STATS_COLOR = new Color(0xAA, 0xAA, 0xCC);

    private final JLabel lblMessage = new JLabel("System Ready.");
    private final JLabel lblStats = new JLabel("PRF: 0.0 Hz | Frames: 0 | Head: 0");

    public StatusBar(ScannerState state) {
        setLayout(new BorderLayout());
        setBackground(BG_COLOR);
        setPreferredSize(new Dimension(800, 26));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x20, 0x20, 0x28)),
                BorderFactory.createEmptyBorder(3, 12, 3, 12)
        ));

        lblMessage.setForeground(MSG_COLOR);
        lblMessage.setFont(new Font("Segoe UI", Font.BOLD, 11));

        lblStats.setForeground(STATS_COLOR);
        lblStats.setFont(new Font("Segoe UI", Font.PLAIN, 11));

        add(lblMessage, BorderLayout.WEST);
        add(lblStats, BorderLayout.EAST);
    }

    public void setMessage(String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            lblMessage.setText(message);
        } else {
            SwingUtilities.invokeLater(() -> lblMessage.setText(message));
        }
    }

    public void updateStats(double prfHz, long totalFrames, int currentHead) {
        String text = String.format("PRF: %.1f Hz | Frames: %,d | Head: %,d", prfHz, totalFrames, currentHead);
        if (SwingUtilities.isEventDispatchThread()) {
            lblStats.setText(text);
        } else {
            SwingUtilities.invokeLater(() -> lblStats.setText(text));
        }
    }
}
