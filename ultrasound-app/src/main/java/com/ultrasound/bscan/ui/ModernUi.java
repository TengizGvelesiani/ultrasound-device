package com.ultrasound.bscan.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.plaf.basic.BasicComboBoxRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * Modern dark-theme UI styling kit with high-DPI anti-aliased components,
 * rounded gradients, glassmorphism highlights, and styled instrumentation controls.
 */
public final class ModernUi {

    public static final Color COLOR_BG_PANEL = new Color(0x16, 0x16, 0x1E);
    public static final Color COLOR_CARD_BG = new Color(0x1E, 0x20, 0x2C);
    public static final Color COLOR_CARD_BORDER = new Color(0x32, 0x36, 0x48);

    public static final Color COLOR_CYAN = new Color(0x00, 0xE5, 0xFF);
    public static final Color COLOR_CYAN_DIM = new Color(0x00, 0x97, 0xA7);
    public static final Color COLOR_TEAL_DARK = new Color(0x00, 0x56, 0x62);
    public static final Color COLOR_TEAL_LIGHT = new Color(0x00, 0x83, 0x8F);

    public static final Color COLOR_GREEN = new Color(0x00, 0xE6, 0x76);
    public static final Color COLOR_GREEN_DARK = new Color(0x00, 0x7E, 0x33);

    public static final Color COLOR_RED = new Color(0xFF, 0x52, 0x52);
    public static final Color COLOR_RED_DARK = new Color(0xB7, 0x1C, 0x1C);

    public static final Color COLOR_SLATE = new Color(0x28, 0x2D, 0x3C);
    public static final Color COLOR_SLATE_LIGHT = new Color(0x36, 0x3D, 0x50);

    public static final Color COLOR_INPUT_BG = new Color(0x12, 0x14, 0x1D);
    public static final Color COLOR_INPUT_BORDER = new Color(0x36, 0x3C, 0x50);

    private ModernUi() {
    }

    /**
     * Anti-aliased rounded button with smooth hover illumination and subtle top-gloss gradient.
     */
    public static class RoundedButton extends JButton {
        private final Color topColor;
        private final Color bottomColor;
        private final Color hoverTop;
        private final Color hoverBottom;
        private final Color borderColor;
        private final int radius;
        private boolean isHovered = false;
        private boolean isPressed = false;

        public RoundedButton(String text, Color baseColor, Color borderColor, int radius) {
            super(text);
            this.topColor = lighten(baseColor, 0.15f);
            this.bottomColor = darken(baseColor, 0.15f);
            this.hoverTop = lighten(baseColor, 0.35f);
            this.hoverBottom = baseColor;
            this.borderColor = borderColor != null ? borderColor : lighten(baseColor, 0.4f);
            this.radius = radius;

            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setForeground(Color.WHITE);
            setFont(new Font("Segoe UI", Font.BOLD, 11));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new Insets(4, 10, 4, 10));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    isHovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    isHovered = false;
                    isPressed = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    isPressed = true;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    isPressed = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            Color c1 = isPressed ? bottomColor : (isHovered ? hoverTop : topColor);
            Color c2 = isPressed ? topColor : (isHovered ? hoverBottom : bottomColor);

            if (!isEnabled()) {
                c1 = new Color(0x22, 0x24, 0x2C);
                c2 = new Color(0x18, 0x1A, 0x20);
            }

            // Fill gradient background
            GradientPaint gp = new GradientPaint(0, 0, c1, 0, h, c2);
            g2.setPaint(gp);
            g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, radius, radius));

            // Top edge glass gloss
            if (isEnabled() && !isPressed) {
                g2.setColor(new Color(255, 255, 255, 35));
                g2.drawLine(radius / 2, 2, w - radius / 2, 2);
            }

            // Outer border
            Color border = isEnabled() ? (isHovered ? lighten(borderColor, 0.2f) : borderColor) : new Color(0x30, 0x34, 0x40);
            g2.setColor(border);
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, radius, radius));

            // Text
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int textX = (w - fm.stringWidth(getText())) / 2;
            int textY = (h + fm.getAscent() - fm.getDescent()) / 2;

            if (isPressed) {
                textY += 1;
            }

            g2.setColor(isEnabled() ? getForeground() : new Color(0x75, 0x7B, 0x8C));
            g2.drawString(getText(), textX, textY);

            g2.dispose();
        }
    }

    /**
     * Prominent high-end instrumentation scan trigger button with vector play badge,
     * vivid glowing outline, two-line adaptive layout, and smooth interactive hover effects.
     */
    public static class HeroScanButton extends JButton {
        private boolean isHovered = false;
        private boolean isPressed = false;

        public HeroScanButton() {
            super();
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(0, 52));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    isHovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    isHovered = false;
                    isPressed = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    isPressed = true;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    isPressed = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int radius = 10;

            Color c1;
            Color c2;
            Color border;

            if (!isEnabled()) {
                c1 = new Color(0x1C, 0x22, 0x2A);
                c2 = new Color(0x14, 0x18, 0x1E);
                border = new Color(0x2E, 0x36, 0x42);
            } else if (isPressed) {
                c1 = new Color(0x00, 0x4D, 0x57);
                c2 = new Color(0x00, 0x6E, 0x7A);
                border = COLOR_CYAN;
            } else if (isHovered) {
                c1 = new Color(0x00, 0x97, 0xA7);
                c2 = new Color(0x00, 0x69, 0x78);
                border = new Color(0x64, 0xFF, 0xDA);
            } else {
                c1 = new Color(0x00, 0x7A, 0x87);
                c2 = new Color(0x00, 0x4D, 0x56);
                border = COLOR_CYAN;
            }

            // Fill background gradient
            GradientPaint gp = new GradientPaint(0, 0, c1, 0, h, c2);
            g2.setPaint(gp);
            g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, radius, radius));

            // Top highlight glass sheen
            if (isEnabled() && !isPressed) {
                g2.setColor(new Color(255, 255, 255, 45));
                g2.drawLine(radius, 2, w - radius, 2);
            }

            // Glowing border
            g2.setColor(border);
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, radius, radius));
            if (isHovered && isEnabled()) {
                g2.setColor(new Color(border.getRed(), border.getGreen(), border.getBlue(), 70));
                g2.draw(new RoundRectangle2D.Float(2, 2, w - 4, h - 4, radius - 1, radius - 1));
            }

            // Circular vector play badge on the left
            int circleD = 28;
            int circleX = 14;
            int circleY = (h - circleD) / 2;

            Color badgeBg = isEnabled() ? new Color(0x00, 0x36, 0x3D, 220) : new Color(0x16, 0x1A, 0x22);
            g2.setColor(badgeBg);
            g2.fillOval(circleX, circleY, circleD, circleD);

            Color badgeBorder = isEnabled() ? (isHovered ? Color.WHITE : COLOR_CYAN) : new Color(0x40, 0x48, 0x56);
            g2.setColor(badgeBorder);
            g2.drawOval(circleX, circleY, circleD, circleD);

            // Vector Triangle Play Icon ▶ inside badge
            int[] triX = {circleX + 11, circleX + 11, circleX + 21};
            int[] triY = {circleY + 8, circleY + 20, circleY + 14};
            g2.setColor(isEnabled() ? (isHovered ? Color.WHITE : COLOR_CYAN) : new Color(0x60, 0x68, 0x78));
            g2.fillPolygon(triX, triY, 3);

            // Primary & Subtitle Text (dynamically adapted to fit available width)
            int textLeft = circleX + circleD + 12;
            int avail = w - textLeft - 10;

            String title = "START FULL CHAMBER SCAN";
            Font titleFont = new Font("Segoe UI", Font.BOLD, 12);
            g2.setFont(titleFont);
            if (g2.getFontMetrics().stringWidth(title) > avail) {
                titleFont = new Font("Segoe UI", Font.BOLD, 11);
                g2.setFont(titleFont);
            }
            if (g2.getFontMetrics().stringWidth(title) > avail) {
                title = "START FULL SCAN";
            }
            if (g2.getFontMetrics().stringWidth(title) > avail) {
                title = "RUN SCAN";
            }

            String subtitle = "Autonomous Serpentine XY";
            Font subFont = new Font("Segoe UI", Font.PLAIN, 10);
            g2.setFont(subFont);
            if (g2.getFontMetrics().stringWidth(subtitle) > avail) {
                subtitle = "Autonomous XY Scan";
            }
            if (g2.getFontMetrics().stringWidth(subtitle) > avail) {
                subtitle = "Automated Grid";
            }

            int titleY = h / 2 - 2;
            int subY = h / 2 + 13;
            if (isPressed) {
                titleY += 1;
                subY += 1;
            }

            g2.setFont(titleFont);
            g2.setColor(isEnabled() ? Color.WHITE : new Color(0x82, 0x8A, 0x9B));
            g2.drawString(title, textLeft, titleY);

            g2.setFont(subFont);
            g2.setColor(isEnabled() ? new Color(0x80, 0xDE, 0xEA) : new Color(0x60, 0x68, 0x76));
            g2.drawString(subtitle, textLeft, subY);

            g2.dispose();
        }
    }

    /**
     * Sleek pill progress bar with dark track, rounded gradient fill, and crisp text overlay.
     */
    public static class ModernProgressBar extends JProgressBar {
        private final Color fillStart;
        private final Color fillEnd;

        public ModernProgressBar(Color fillStart, Color fillEnd) {
            super(0, 100);
            this.fillStart = fillStart;
            this.fillEnd = fillEnd;
            setStringPainted(true);
            setOpaque(false);
            setBorderPainted(false);
            setFont(new Font("Segoe UI", Font.BOLD, 10));
            setPreferredSize(new Dimension(0, 22));
            setMaximumSize(new Dimension(Short.MAX_VALUE, 22));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int radius = 8;

            // Recessed dark track
            g2.setColor(new Color(0x10, 0x12, 0x1A));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, radius, radius));
            g2.setColor(new Color(0x28, 0x2D, 0x3D));
            g2.draw(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, radius, radius));

            // Fill gradient
            double pct = getPercentComplete();
            if (pct > 0.0) {
                int fillW = (int) Math.round((w - 2) * pct);
                fillW = Math.max(fillW, radius);
                GradientPaint gp = new GradientPaint(1, 1, fillStart, fillW, h, fillEnd);
                g2.setPaint(gp);
                g2.fill(new RoundRectangle2D.Float(1, 1, fillW, h - 2, radius - 1, radius - 1));

                // Top highlight
                g2.setColor(new Color(255, 255, 255, 50));
                g2.drawLine(radius, 2, fillW - radius / 2, 2);
            }

            // Crisp text overlay
            String str = getString();
            if (str != null && !str.isEmpty()) {
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int tx = (w - fm.stringWidth(str)) / 2;
                int ty = (h + fm.getAscent() - fm.getDescent()) / 2;

                // Subtle shadow
                g2.setColor(new Color(0, 0, 0, 160));
                g2.drawString(str, tx + 1, ty + 1);

                g2.setColor(Color.WHITE);
                g2.drawString(str, tx, ty);
            }

            g2.dispose();
        }
    }

    /**
     * Styles JSpinner components with dark themes, eliminating harsh white backgrounds.
     */
    public static void styleSpinner(JSpinner spinner) {
        spinner.setBackground(COLOR_INPUT_BG);
        spinner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_INPUT_BORDER, 1, true),
                BorderFactory.createEmptyBorder(2, 4, 2, 2)
        ));

        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
            JTextField tf = defaultEditor.getTextField();
            tf.setBackground(COLOR_INPUT_BG);
            tf.setForeground(new Color(0xEE, 0xF2, 0xF6));
            tf.setCaretColor(COLOR_CYAN);
            tf.setFont(new Font("Consolas", Font.BOLD, 12));
            tf.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            tf.setHorizontalAlignment(JTextField.RIGHT);
        }

        // Dark-style arrow buttons inside the spinner
        for (Component c : spinner.getComponents()) {
            if (c instanceof JButton btn) {
                btn.setBackground(new Color(0x20, 0x24, 0x30));
                btn.setForeground(new Color(0x80, 0xDE, 0xEA));
                btn.setBorder(BorderFactory.createLineBorder(new Color(0x2B, 0x30, 0x42)));
                btn.setFocusable(false);
            }
        }
    }

    /**
     * Styles JComboBox components with dark themes.
     */
    public static <T> void styleComboBox(JComboBox<T> combo) {
        combo.setBackground(COLOR_INPUT_BG);
        combo.setForeground(Color.WHITE);
        combo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        combo.setBorder(BorderFactory.createLineBorder(COLOR_INPUT_BORDER, 1, true));
        combo.setFocusable(false);
        combo.setRenderer(new BasicComboBoxRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setBackground(isSelected ? new Color(0x00, 0x7A, 0x87) : COLOR_INPUT_BG);
                setForeground(Color.WHITE);
                setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                return this;
            }
        });
    }

    public static Color lighten(Color c, float amount) {
        int r = Math.min(255, (int) (c.getRed() + (255 - c.getRed()) * amount));
        int g = Math.min(255, (int) (c.getGreen() + (255 - c.getGreen()) * amount));
        int b = Math.min(255, (int) (c.getBlue() + (255 - c.getBlue()) * amount));
        return new Color(r, g, b, c.getAlpha());
    }

    public static Color darken(Color c, float amount) {
        int r = Math.max(0, (int) (c.getRed() * (1.0f - amount)));
        int g = Math.max(0, (int) (c.getGreen() * (1.0f - amount)));
        int b = Math.max(0, (int) (c.getBlue() * (1.0f - amount)));
        return new Color(r, g, b, c.getAlpha());
    }
}
