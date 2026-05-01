package com.scale.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Small Swing status window shown to the operator.
 * Displays scale serial-port state and frontend client connection state.
 * All public methods are thread-safe — they schedule updates on the EDT.
 */
public final class StatusWindow extends JFrame {

    private static final Color COL_OK   = new Color(0x27AE60);
    private static final Color COL_ERR  = new Color(0xC0392B);
    private static final Color COL_WARN = new Color(0xE67E22);
    private static final Color COL_IDLE = new Color(0x95A5A6);

    private final JLabel scaleIndicator;
    private final JLabel scaleValue;
    private final JLabel clientIndicator;
    private final JLabel clientValue;

    public StatusWindow() {
        super("Scale Server");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);

        scaleIndicator  = indicator();
        scaleValue      = value("Connecting...");
        clientIndicator = indicator();
        clientValue     = value("No client connected");

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 32));

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;

        addRow(panel, c, 0, "Scale",  scaleIndicator,  scaleValue);
        addRow(panel, c, 1, "Client", clientIndicator, clientValue);

        getContentPane().add(panel);
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(null);
        setVisible(true);
    }

    public void updateScale(boolean connected, String port) {
        SwingUtilities.invokeLater(() -> {
            if (connected) {
                scaleIndicator.setForeground(COL_OK);
                scaleValue.setText("Connected on " + port);
            } else {
                scaleIndicator.setForeground(COL_ERR);
                scaleValue.setText("Disconnected — check serial cable");
            }
        });
    }

    public void updateClient(boolean connected) {
        SwingUtilities.invokeLater(() -> {
            if (connected) {
                clientIndicator.setForeground(COL_OK);
                clientValue.setText("Frontend connected");
            } else {
                clientIndicator.setForeground(COL_WARN);
                clientValue.setText("No client connected");
            }
        });
    }

    private static void addRow(JPanel p, GridBagConstraints c, int row,
                                String title, JLabel ind, JLabel val) {
        c.gridy = row;
        c.gridx = 0; c.insets = new Insets(6, 0, 6, 14); p.add(header(title), c);
        c.gridx = 1; c.insets = new Insets(6, 0, 6,  8); p.add(ind, c);
        c.gridx = 2; c.insets = new Insets(6, 0, 6,  0); p.add(val, c);
    }

    private static JLabel header(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        return l;
    }

    private static JLabel indicator() {
        JLabel l = new JLabel("●");   // ●
        l.setFont(l.getFont().deriveFont(16f));
        l.setForeground(COL_IDLE);
        return l;
    }

    private static JLabel value(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(13f));
        return l;
    }
}
