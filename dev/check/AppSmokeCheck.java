package check;

import com.labsync.client.UniBookClientApp;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * DEV-ONLY automated UI walkthrough (run under xvfb-run with mocks started).
 * Steps are given as args, e.g.:  connect shot:a.png book:S05 shot:b.png ok wait:3000 shot:c.png
 * The app's main() is used unchanged; this class only clicks buttons and takes screenshots.
 */
public class AppSmokeCheck {

    public static void main(String[] args) throws Exception {
        UniBookClientApp.main(new String[0]);
        Thread.sleep(1500);
        for (String step : args) {
            System.out.println("step " + step);
            if (step.equals("connect")) {
                onEdt(() -> {
                    find(c -> c instanceof JPasswordField, JPasswordField.class).setText("pass123");
                    button("Connect").doClick();
                });
                Thread.sleep(2500);
            } else if (step.startsWith("host:")) {
                onEdt(() -> find(c -> c instanceof JTextField && !(c instanceof JPasswordField)
                        && "localhost".equals(((JTextField) c).getText()), JTextField.class)
                        .setText(step.substring(5)));
            } else if (step.startsWith("book:")) {
                String seat = step.substring(5);
                SwingUtilities.invokeLater(() -> button(seat).doClick());   // opens modal confirm
                Thread.sleep(800);
                SwingUtilities.invokeLater(() -> button("Yes").doClick());
                Thread.sleep(1500);
            } else if (step.equals("ok")) {
                SwingUtilities.invokeLater(() -> button("OK").doClick());
                Thread.sleep(800);
            } else if (step.startsWith("wait:")) {
                Thread.sleep(Long.parseLong(step.substring(5)));
            } else if (step.startsWith("shot:")) {
                // Paint every visible window at its screen position (no Robot/screencast needed)
                BufferedImage img = new BufferedImage(900, 720, BufferedImage.TYPE_INT_RGB);
                onEdt(() -> {
                    Graphics2D g = img.createGraphics();
                    for (Window w : Window.getWindows()) {
                        if (w.isShowing()) {
                            Graphics2D wg = (Graphics2D) g.create(w.getX(), w.getY(), w.getWidth(), w.getHeight());
                            w.paint(wg);
                            wg.dispose();
                        }
                    }
                    g.dispose();
                });
                ImageIO.write(img, "png", new File(step.substring(5)));
            }
        }
        System.exit(0);
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static AbstractButton button(String text) {
        return find(c -> c instanceof AbstractButton
                        && (text.equals(((AbstractButton) c).getText())
                            || ((AbstractButton) c).getText().contains(">" + text + "<")),   // HTML seat tiles
                AbstractButton.class);
    }

    private static <T> T find(Predicate<Component> p, Class<T> type) {
        Window[] windows = Window.getWindows();
        for (int i = windows.length - 1; i >= 0; i--) {     // newest window (dialogs) first
            if (windows[i].isShowing()) {
                Component c = search(windows[i], p);
                if (c != null) {
                    return type.cast(c);
                }
            }
        }
        throw new IllegalStateException("component not found");
    }

    private static Component search(Container root, Predicate<Component> p) {
        for (Component c : root.getComponents()) {
            if (p.test(c)) {
                return c;
            }
            if (c instanceof Container) {
                Component found = search((Container) c, p);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
