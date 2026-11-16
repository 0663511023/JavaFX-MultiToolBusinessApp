package application;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the text overrides for the chosen text size and typeface. The app's stylesheet fixes each font size in
 * pixels, so this reads it and writes a second stylesheet in which every size is multiplied by the scale. Only the
 * UI typeface changes with the choice; the monospace code fonts keep their own. Pure logic with no JavaFX.
 */
public final class FontCss {

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern BLOCK = Pattern.compile("([^{}]+)\\{([^{}]*)\\}");
    private static final Pattern SIZE = Pattern.compile("-fx-font-size:\\s*([0-9.]+)px");
    private static final Pattern FAMILY = Pattern.compile("-fx-font-family:\\s*([^;]+)");

    /** The typeface the app's stylesheet uses for its interface text. Only this one follows the choice. */
    static final String UI_FAMILY = "Segoe UI";

    private FontCss() {
    }

    /**
     * The overrides for one rule each: the same selector, with the font size multiplied by scale and, for the
     * interface typeface, the chosen family. Rules with no font settings are left out.
     */
    public static String build(String baseCss, double scale, String family) {
        String css = COMMENT.matcher(baseCss).replaceAll("");
        StringBuilder out = new StringBuilder();
        out.append("/* Generated from the text size and typeface in Settings. Replaced when they change. */\n");
        Matcher block = BLOCK.matcher(css);
        while (block.find()) {
            String selectors = block.group(1).trim();
            String body = block.group(2);
            Matcher size = SIZE.matcher(body);
            Matcher face = FAMILY.matcher(body);
            boolean sized = size.find();
            boolean uiFace = face.find() && face.group(1).contains(UI_FAMILY);
            if (!sized && !uiFace) {
                continue;
            }
            out.append(selectors).append(" {");
            if (sized) {
                double px = Double.parseDouble(size.group(1));
                out.append(" -fx-font-size: ").append(format(px * scale)).append("px;");
            }
            if (uiFace) {
                out.append(" -fx-font-family: \"").append(family).append("\";");
            }
            out.append(" }\n");
        }
        return out.toString();
    }

    static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
