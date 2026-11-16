package application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/**
 * Small solid icons drawn as SVG paths on a 24 x 24 grid. There are no image files and no
 * external libraries. Holes in an icon use the even-odd fill rule, and the colour comes from CSS:
 * every icon carries the class "app-icon", which sets -fx-fill for each theme.
 */
final class IconLibrary {

    /** The size of the grid every icon is drawn on. */
    private static final double GRID = 24;

    /** Colour family for a section. The class name is the CSS style of its Home card badge. */
    enum Category {
        PRICING("icon-badge-pricing"),
        INCOME("icon-badge-income"),
        SHIPPING("icon-badge-shipping"),
        SAVINGS("icon-badge-savings"),
        RECORDS("icon-badge-records"),
        CLIENTS("icon-badge-clients"),
        PLAIN("icon-badge-plain");

        final String badgeClass;

        Category(String badgeClass) {
            this.badgeClass = badgeClass;
        }
    }

    /** One icon per tool, plus one for the Home page. */
    enum Icon {
        HOME("M12 3 L22 11 H19 V21 H14 V15 H10 V21 H5 V11 H2 Z"),
        MARGIN("M3 3 H11 L21 13 L13 21 L3 11 Z" + circle(7.5, 7.5, 1.8)),
        CARD(rect(2, 5, 20, 14) + rect(2, 9, 20, 2) + rect(5, 14, 4, 3)),
        RECEIPT("M5 2 H19 V22 L16.5 20.5 L14 22 L12 20.5 L10 22 L7.5 20.5 L5 22 Z"
                + rect(8, 7, 8, 2) + rect(8, 11, 8, 2) + rect(8, 15, 5, 2)),
        BANKNOTE(rect(2, 6, 20, 12) + circle(12, 12, 2.8)),
        CLOCK(circle(12, 12, 9) + circle(12, 12, 7.4)
                + "M11 6.5 H13 V11.3 L15.6 12.9 L14.6 14.6 L11 12.4 Z"),
        SHIELD("M12 2 L20 5 V11 C20 16.5 16.8 20.2 12 22 C7.2 20.2 4 16.5 4 11 V5 Z"
                + "M7.6 11.4 L9 10 L10.9 11.9 L15 7.8 L16.4 9.2 L10.9 14.7 Z"),
        BOX("M12 2 L21 6.5 V17.5 L12 22 L3 17.5 V6.5 Z" + "M12 6 L17 8.5 L12 11 L7 8.5 Z"),
        BARS(rect(3, 13, 4, 8) + rect(10, 9, 4, 12) + rect(17, 4, 4, 17) + rect(2, 21, 20, 1.5)),
        CALENDAR(rect(3, 5, 18, 16) + rect(5, 9, 14, 10) + rect(7, 2, 2, 3) + rect(15, 2, 2, 3)
                + rect(7, 12, 3, 3) + rect(13, 12, 3, 3)),
        // A person: a head and shoulders, for the client profiles
        USERS(circle(12, 7.5, 4) + "M4 21 C4 16.5 7.5 13.5 12 13.5 C16.5 13.5 20 16.5 20 21 Z"),
        LIST(rect(3, 5, 2, 2) + rect(3, 11, 2, 2) + rect(3, 17, 2, 2)
                + rect(8, 5, 13, 2) + rect(8, 11, 13, 2) + rect(8, 17, 13, 2)),
        // A panel frame with its left side filled in: the sidebar, shown or hidden
        SIDEBAR(rect(2, 4, 20, 16) + rect(3.5, 5.5, 17, 13) + rect(3.5, 5.5, 6, 13)),
        // A cog: a body with teeth and a round hole in the middle, for Settings
        GEAR(circle(12, 12, 7) + rect(10.5, 2.5, 3, 2.6) + rect(10.5, 18.9, 3, 2.6)
                + rect(2.5, 10.5, 2.6, 3) + rect(18.9, 10.5, 2.6, 3) + circle(12, 12, 2.8)),
        // A ring with an arrowhead, for Refresh
        REFRESH(circle(12, 12, 9) + circle(12, 12, 6.5) + "M21 1 L24 6 L18 4 Z"),
        // A bin with its lid and two slots, for delete
        TRASH(rect(4, 5, 16, 2) + rect(9, 2.5, 6, 2.5) + "M6 8 H18 V21 H6 Z"
                + rect(9.5, 11, 1.8, 7) + rect(12.7, 11, 1.8, 7));

        final String path;

        Icon(String path) {
            this.path = path;
        }
    }

    private IconLibrary() {
    }

    /** The colour family of a sidebar section, used for its Home cards. */
    static Category categoryFor(String sectionTitle) {
        return switch (sectionTitle) {
            case "Pricing & Fees" -> Category.PRICING;
            case "Income & Tax" -> Category.INCOME;
            case "Shipping" -> Category.SHIPPING;
            case "Savings & Loans" -> Category.SAVINGS;
            case "Records" -> Category.RECORDS;
            case "Clients" -> Category.CLIENTS;
            default -> Category.PLAIN;
        };
    }

    /**
     * An icon at the given scale (1.0 is 24 px). The node is sized to the scaled icon, so it takes
     * exactly the space it draws in, and it can be placed in any layout.
     */
    static Node icon(Icon icon, double scale) {
        SVGPath path = new SVGPath();
        path.setContent(icon.path);
        path.setFillRule(FillRule.EVEN_ODD);
        path.getStyleClass().add("app-icon");
        // Scale about the top-left corner, so the drawn icon starts at (0, 0) of its box
        Scale transform = new Scale(1, 1, 0, 0);
        path.getTransforms().add(transform);

        Pane box = new Pane(path);
        Sized entry = new Sized(box, transform, scale);
        sized.add(entry);
        resize(entry);
        return box;
    }

    /** Resizes every icon to the current text size, so the icons grow and shrink with the text. */
    static void refreshSizes() {
        for (Sized entry : sized) {
            resize(entry);
        }
    }

    private static void resize(Sized entry) {
        double factor = entry.scale * FontSettings.scale();
        entry.transform.setX(factor);
        entry.transform.setY(factor);
        double size = GRID * factor;
        entry.box.setMinSize(size, size);
        entry.box.setPrefSize(size, size);
        entry.box.setMaxSize(size, size);
    }

    /** An icon on screen: its box, its drawing scale, and the scale it was given when it was made. */
    private static final class Sized {
        final Pane box;
        final Scale transform;
        final double scale;

        Sized(Pane box, Scale transform, double scale) {
            this.box = box;
            this.transform = transform;
            this.scale = scale;
        }
    }

    /** Every icon made so far, so all of them can follow the text size. Icons are made on the JavaFX thread only. */
    private static final List<Sized> sized = new ArrayList<>();

    // ---- path helpers, all on the 24 x 24 grid

    /** A filled rectangle (also used as a hole inside another shape). */
    private static String rect(double x, double y, double w, double h) {
        return "M" + n(x) + " " + n(y) + " H" + n(x + w) + " V" + n(y + h) + " H" + n(x) + " Z";
    }

    /** A circle drawn with four cubic curves (also used as a hole). */
    private static String circle(double cx, double cy, double r) {
        double k = r * 0.5523;
        return "M" + n(cx) + " " + n(cy - r)
                + " C" + n(cx + k) + " " + n(cy - r) + " " + n(cx + r) + " " + n(cy - k) + " " + n(cx + r) + " " + n(cy)
                + " C" + n(cx + r) + " " + n(cy + k) + " " + n(cx + k) + " " + n(cy + r) + " " + n(cx) + " " + n(cy + r)
                + " C" + n(cx - k) + " " + n(cy + r) + " " + n(cx - r) + " " + n(cy + k) + " " + n(cx - r) + " " + n(cy)
                + " C" + n(cx - r) + " " + n(cy - k) + " " + n(cx - k) + " " + n(cy - r) + " " + n(cx) + " " + n(cy - r)
                + " Z";
    }

    /** Formats a coordinate with a dot as the decimal point, whatever the regional settings. */
    private static String n(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
