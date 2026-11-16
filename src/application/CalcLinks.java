package application;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lets one tool hand its figures to another, such as Margin &amp; Markup to Scenario Comparison. A link exists only
 * where two tools share a figure. Nothing is calculated on arrival: the receiving tool fills its inputs, and the
 * user presses its own Calculate button. Values are plain text, keyed by name. Pure logic, no JavaFX.
 */
final class CalcLinks {

    private static final Map<String, Consumer<Map<String, String>>> RECEIVERS = new HashMap<>();
    private static Consumer<String> opener = name -> { };

    private CalcLinks() {
    }

    /** Registers how a tool takes in the figures sent to it. */
    static void receiver(String toolName, Consumer<Map<String, String>> handler) {
        RECEIVERS.put(toolName, handler);
    }

    /** Set by the Business tab: opens a tool by its name. */
    static void setOpener(Consumer<String> open) {
        opener = open == null ? name -> { } : open;
    }

    /** Sends figures to a tool and opens it. Returns false, and opens nothing, if that tool takes no figures. */
    static boolean send(String toolName, Map<String, String> values) {
        Consumer<Map<String, String>> handler = RECEIVERS.get(toolName);
        if (handler == null) {
            return false;
        }
        handler.accept(Map.copyOf(values));
        opener.accept(toolName);
        return true;
    }
}
