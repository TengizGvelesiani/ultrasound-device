package com.ultrasound.bscan.ui;

import com.ultrasound.bscan.model.ScannerState;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
        setPreferredSize(new Dimension(800, 24));
        setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));

        lblMessage.setForeground(MSG_COLOR);
        lblMessage.setFont(new Font("SansSerif", Font.BOLD, 11));

        lblStats.setForeground(STATS_COLOR);
        lblStats.setFont(new Font("SansSerif", Font.PLAIN, 11));

        add(lblMessage, BorderLayout.WEST);
        add(lblStats, BorderLayout.EAST);
    }

    public void setMessage(String message) {
        lblMessage.setText(message);
    }

    public void updateStats(double prfHz, long totalFrames, int currentHead) {
        lblStats.setText(String.format("PRF: %.1f Hz | Frames: %,d | Head: %,d", prfHz, totalFrames, currentHead));
    }
}
