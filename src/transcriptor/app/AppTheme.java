package transcriptor.app;

import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

public final class AppTheme {
    public static final Color INK = new Color(32, 37, 46);
    public static final Color MUTED = new Color(102, 111, 126);
    public static final Color BACKGROUND = new Color(243, 244, 239);
    public static final Color PANEL = new Color(251, 251, 248);
    public static final Color BORDER = new Color(214, 219, 211);
    public static final Color ACCENT = new Color(16, 113, 97);
    public static final Color ACCENT_DARK = new Color(10, 76, 71);
    public static final Color WARM = new Color(245, 174, 83);
    public static final Color SUCCESS = new Color(27, 132, 84);
    public static final Color DANGER = new Color(181, 65, 44);

    private AppTheme() {
    }

    public static void configure() {
        Font baseFont = pickFont(14f, Font.PLAIN);
        Font headingFont = pickFont(15f, Font.BOLD);

        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", INK);
        UIManager.put("TextField.font", baseFont);
        UIManager.put("TextArea.font", baseFont);
        UIManager.put("ComboBox.font", baseFont);
        UIManager.put("Button.font", headingFont);
        UIManager.put("List.font", baseFont);
        UIManager.put("ScrollPane.border", BorderFactory.createEmptyBorder());
    }

    public static Font pickFont(float size, int style) {
        String[] options = {"Segoe UI Variable Text", "Segoe UI", "SansSerif"};
        for (String family : options) {
            Font font = new Font(family, style, Math.round(size));
            if (family.equalsIgnoreCase(font.getFamily()) || "Dialog".equals(font.getFamily()) == false) {
                return font.deriveFont(style, size);
            }
        }
        return new Font("SansSerif", style, Math.round(size));
    }

    public static Border cardBorder() {
        return new CompoundBorder(
            new LineBorder(BORDER, 1, true),
            new EmptyBorder(16, 16, 16, 16)
        );
    }
}

