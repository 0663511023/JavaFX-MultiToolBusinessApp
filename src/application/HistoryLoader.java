package application;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends a saved calculation back to the calculator that made it. Each calculator registers how to
 * read its own history lines and fill its own inputs. Nothing is recalculated here, so the history
 * does not gain a duplicate entry: the user presses Calculate when they are ready.
 */
final class HistoryLoader {

    private static final Map<String, Function<HistoryRecord, String>> LOADERS = new HashMap<>();

    private HistoryLoader() {
    }

    /**
     * Registers how a calculator fills its inputs from one of its saved lines. The loader returns
     * null when it succeeds, or a message saying why it could not.
     */
    static void register(String calculatorKey, Function<HistoryRecord, String> loader) {
        LOADERS.put(calculatorKey, loader);
    }

    /** Fills the calculator's inputs from a saved line. Returns null on success, or a message saying why not. */
    static String load(HistoryRecord record) {
        Function<HistoryRecord, String> loader = LOADERS.get(record.calculator);
        if (loader == null) {
            return "This calculation cannot be sent to a calculator.";
        }
        try {
            return loader.apply(record);
        } catch (RuntimeException ex) {
            return "This saved line could not be read.";
        }
    }

    /** The first match of the pattern in the text, or null if there is none. */
    static Matcher find(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher : null;
    }
}
