package check;

import com.labsync.client.SeatGridPanel;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Stage 4 check: shows SeatGridPanel with fake data (no network).
 * Simulates a UDP update from a background thread after 1 s.
 * Usage: java check.GridPreview [screenshot.png]   (with a file: saves and exits)
 */
public class GridPreview {
    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        SeatGridPanel[] holder = new SeatGridPanel[1];
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            SeatGridPanel grid = new SeatGridPanel();
            Map<String, String> seats = new LinkedHashMap<>();
            for (int i = 1; i <= 12; i++) {
                seats.put(String.format("S%02d", i), i == 3 ? "BOOKED" : i == 7 ? "RESERVED" : "FREE");
            }
            grid.showLab("LAB_B", seats);
            grid.setOnSeatClicked(seat -> System.out.println("Clicked " + seat));
            JFrame f = new JFrame("Grid preview");
            f.add(grid);
            f.setSize(500, 320);
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setVisible(true);
            holder[0] = grid;
            frame[0] = f;
        });

        // Simulate Member 3's UDP thread calling the handler (off the EDT)
        Thread.sleep(1000);
        holder[0].onSeatUpdate("LAB_B", "S12", "BOOKED", "student2");
        holder[0].onSeatUpdate("LAB_A", "S01", "BOOKED", "x");   // other lab: ignored
        Thread.sleep(500);

        if (args.length > 0) {
            BufferedImage img = new BufferedImage(frame[0].getWidth(), frame[0].getHeight(), BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(() -> frame[0].paint(img.getGraphics()));
            ImageIO.write(img, "png", new File(args[0]));
            System.out.println("S12 status now: " + holder[0].getStatus("S12") + "; screenshot -> " + args[0]);
            System.exit(0);
        }
    }
}
