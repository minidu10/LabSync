package com.labsync.client;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URISyntaxException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Shows a booking receipt: receipt id, SHA-256 hash, and the unibook:// URI
 * split into its parts with java.net.URI (via NetworkResolver).
 */
public final class ReceiptDialog {

    private ReceiptDialog() { }

    public static void show(Component parent, String labId, String seatId, Protocol.BookResult r) {
        JPanel panel = new JPanel(new GridBagLayout());
        int row = 0;

        JLabel heading = new JLabel("✔ Seat " + seatId + " in " + labId + " is booked");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        heading.setForeground(new Color(46, 125, 50));
        addFull(panel, heading, row++);

        addRow(panel, "Receipt ID", r.receiptId(), row++);
        addRow(panel, "SHA-256", r.hash(), row++);
        addRow(panel, "Receipt URI", r.uri(), row++);

        try {
            NetworkResolver.ReceiptUri u = NetworkResolver.parseReceiptUri(r.uri());
            addSection(panel, "URI parsed with java.net.URI", row++);
            addRow(panel, "  scheme", u.scheme(), row++);
            addRow(panel, "  host", u.host(), row++);
            addRow(panel, "  port", String.valueOf(u.port()), row++);
            addRow(panel, "  receipt", u.receiptId(), row++);
        } catch (URISyntaxException e) {
            addSection(panel, "Could not parse URI: " + e.getReason(), row++);
        }

        JButton copyUri = new JButton("Copy URI");
        copyUri.addActionListener(e -> copy(r.uri()));
        JButton copyHash = new JButton("Copy hash");
        copyHash.addActionListener(e -> copy(r.hash()));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(copyUri);
        buttons.add(javax.swing.Box.createHorizontalStrut(8));
        buttons.add(copyHash);
        addFull(panel, buttons, row);

        JOptionPane.showMessageDialog(parent, panel, "Booking confirmed", JOptionPane.PLAIN_MESSAGE);
    }

    private static void addRow(JPanel p, String label, String value, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(3, 0, 3, 10);
        c.anchor = GridBagConstraints.WEST;
        JLabel l = new JLabel(label);
        l.setForeground(new Color(90, 90, 90));
        p.add(l, c);

        // read-only text field: looks like a label but the value can be selected
        JTextField f = new JTextField(value, Math.max(24, Math.min(value.length() + 1, 66)));
        f.setEditable(false);
        f.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        f.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 0);
        p.add(f, c);
    }

    private static void addSection(JPanel p, String text, int row) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        addFull(p, l, row);
    }

    private static void addFull(JPanel p, Component comp, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 0, 6, 0);
        p.add(comp, c);
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }
}
