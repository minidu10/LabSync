package com.labsync.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicButtonUI;

/**
 * Colour-coded seat grid for one lab. Pure UI: it knows nothing about sockets.
 *
 * - showLab(...)       shows a LIST result
 * - setSeatStatus(...) changes one seat
 * - markMine(...)      remembers a seat this user booked (shown as YOURS)
 * - onSeatUpdate(...)  thread-safe entry point for UDP updates (Member 3)
 * - a click on a FREE seat is reported through the seat-click callback;
 *   the app decides what to do (send BOOK), keeping networking out of here.
 *
 * All methods except onSeatUpdate must be called on the Swing thread (EDT).
 */
public class SeatGridPanel extends JPanel implements SeatUpdateHandler {

    private static final long serialVersionUID = 1L;
    private static final int COLUMNS = 5;

    public static final Color FREE = new Color(67, 160, 71);
    public static final Color BOOKED = new Color(211, 47, 47);
    public static final Color RESERVED = new Color(245, 158, 11);
    public static final Color MINE = new Color(30, 110, 220);
    public static final Color UNKNOWN = Color.GRAY;
    private static final Color DIM_TARGET = new Color(205, 205, 205);

    private final JPanel grid = new JPanel();
    private final JLabel title = new JLabel("Not connected - enter your details and click Connect");
    private final JLabel summary = new JLabel(" ");
    private final Map<String, JButton> buttons = new LinkedHashMap<>();
    private final Map<String, String> statuses = new LinkedHashMap<>();
    private final Set<String> mySeats = new HashSet<>();      // "LAB_A/S05"
    private String currentLab;
    private String currentUser;
    private Consumer<String> onSeatClicked = seat -> { };
    private boolean interactive = true;

    public SeatGridPanel() {
        super(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        summary.setForeground(new Color(90, 90, 90));
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(title, BorderLayout.WEST);
        header.add(summary, BorderLayout.EAST);

        grid.setOpaque(false);
        add(header, BorderLayout.NORTH);
        add(grid, BorderLayout.CENTER);
        add(buildLegend(), BorderLayout.SOUTH);
    }

    public void setOnSeatClicked(Consumer<String> callback) {
        this.onSeatClicked = callback;
    }

    /** Used to recognise our own bookings in UDP updates. */
    public void setCurrentUser(String userId) {
        this.currentUser = userId;
    }

    public String getCurrentLab() {
        return currentLab;
    }

    public String getStatus(String seatId) {
        return statuses.get(seatId);
    }

    /** Shows a LIST reply. Same lab and same seats -> just recolour (no flicker). */
    public void showLab(String labId, Map<String, String> seats) {
        if (labId.equals(currentLab) && buttons.keySet().equals(seats.keySet())) {
            seats.forEach(this::setSeatStatus);
            return;
        }
        currentLab = labId;
        title.setText(labId);
        grid.removeAll();
        buttons.clear();
        statuses.clear();
        grid.setLayout(new GridLayout(0, COLUMNS, 8, 8));   // 0 rows = "as many as needed"

        for (Map.Entry<String, String> e : seats.entrySet()) {
            JButton tile = createTile(e.getKey());
            buttons.put(e.getKey(), tile);
            grid.add(tile);
            setSeatStatus(e.getKey(), e.getValue());
        }
        grid.revalidate();
        grid.repaint();
    }

    /** Updates one seat. Unknown seat ids are ignored. */
    public void setSeatStatus(String seatId, String status) {
        if (!buttons.containsKey(seatId)) {
            return;
        }
        statuses.put(seatId, status);
        if ("FREE".equals(status)) {
            mySeats.remove(key(seatId));     // a cancelled seat is no longer ours
        }
        refreshTile(seatId);
        updateSummary();
    }

    /** Called after our own BOOK_OK. */
    public void markMine(String seatId) {
        mySeats.add(key(seatId));
        refreshTile(seatId);
        updateSummary();
    }

    /** Called from the UDP thread - hop onto the EDT before touching Swing. */
    @Override
    public void onSeatUpdate(String labId, String seatId, String status, String userId) {
        SwingUtilities.invokeLater(() -> {
            if (!labId.equals(currentLab)) {
                return;
            }
            if ("BOOKED".equals(status) && userId != null && userId.equals(currentUser)) {
                mySeats.add(key(seatId));
            }
            setSeatStatus(seatId, status);
        });
    }

    /** While disconnected the seats are dimmed and clicks are ignored. */
    public void setInteractive(boolean on) {
        interactive = on;
        buttons.keySet().forEach(this::refreshTile);
    }

    // ---------------------------------------------------------------------

    private JButton createTile(String seatId) {
        JButton b = new JButton();
        b.setUI(new BasicButtonUI());   // plain button so our colours show under any look-and-feel
        b.setOpaque(true);
        b.setFocusPainted(false);
        b.setRolloverEnabled(true);
        b.setForeground(Color.WHITE);
        b.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 6));
        b.addActionListener(ev -> {
            if (isClickable(seatId)) {
                onSeatClicked.accept(seatId);
            }
        });
        b.getModel().addChangeListener(ev -> refreshTile(seatId));   // hover highlight
        return b;
    }

    private boolean isClickable(String seatId) {
        return interactive && "FREE".equals(statuses.get(seatId));
    }

    private void refreshTile(String seatId) {
        JButton b = buttons.get(seatId);
        String status = statuses.get(seatId);
        if (b == null || status == null) {
            return;
        }
        boolean mine = mySeats.contains(key(seatId)) && "BOOKED".equals(status);
        String shown = mine ? "YOURS" : status;
        Color colour = mine ? MINE : colourFor(status);

        if (!interactive) {
            colour = blend(colour, DIM_TARGET, 0.6);
        } else if (isClickable(seatId) && b.getModel().isRollover()) {
            colour = colour.brighter();
        }
        b.setBackground(colour);

        String text = "<html><center><b style='font-size:13px'>" + seatId
                + "</b><br><span style='font-size:9px'>" + shown + "</span></center></html>";
        if (!text.equals(b.getText())) {
            b.setText(text);
        }
        b.setCursor(Cursor.getPredefinedCursor(isClickable(seatId) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        b.setToolTipText(isClickable(seatId) ? "Click to book " + seatId : seatId + ": " + shown);
    }

    private void updateSummary() {
        long free = statuses.values().stream().filter("FREE"::equals).count();
        long booked = statuses.values().stream().filter("BOOKED"::equals).count();
        long reserved = statuses.values().stream().filter("RESERVED"::equals).count();
        summary.setText(free + " free  ·  " + booked + " booked  ·  " + reserved + " reserved");
    }

    private String key(String seatId) {
        return currentLab + "/" + seatId;
    }

    private static Color colourFor(String status) {
        switch (status) {
            case "FREE":     return FREE;
            case "BOOKED":   return BOOKED;
            case "RESERVED": return RESERVED;
            default:         return UNKNOWN;
        }
    }

    private static Color blend(Color a, Color b, double t) {
        return new Color(
                (int) (a.getRed() * (1 - t) + b.getRed() * t),
                (int) (a.getGreen() * (1 - t) + b.getGreen() * t),
                (int) (a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    private static JPanel buildLegend() {
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        legend.setOpaque(false);
        legend.add(swatch("Free", FREE));
        legend.add(swatch("Booked", BOOKED));
        legend.add(swatch("Reserved", RESERVED));
        legend.add(swatch("Yours", MINE));
        return legend;
    }

    private static JLabel swatch(String text, Color c) {
        JLabel l = new JLabel("■ " + text);
        l.setForeground(c.darker());
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }
}
