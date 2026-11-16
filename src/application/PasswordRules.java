package application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules a new backup password must pass, in the same spirit as the checks web browsers make before they
 * accept a new password: a minimum length, a mix of character kinds, and no common words or simple patterns.
 * Every rule is listed, so the dialog can show all of them and tick each one as it is met. Pure logic, no JavaFX.
 */
public final class PasswordRules {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    /** Passwords that are too easy to guess, compared without their digits and symbols and in lower case. */
    private static final Set<String> COMMON = Set.of(
            "password", "passwort", "qwerty", "qwertyuiop", "letmein", "welcome", "iloveyou", "admin",
            "login", "monkey", "dragon", "football", "baseball", "sunshine", "princess", "master", "shadow",
            "superman", "batman", "trustno", "abc", "abcdef", "marginiq", "southafrica", "capetown",
            "johannesburg", "pretoria", "durban", "rand", "business", "invoice", "quotation");

    /** One requirement: the text shown to the user, and whether the password meets it. */
    public static final class Rule {
        public final String text;
        public final boolean met;

        Rule(String text, boolean met) {
            this.text = text;
            this.met = met;
        }
    }

    private PasswordRules() {
    }

    /** Every requirement, in the order they are shown, each marked met or not for this password. */
    public static List<Rule> rules(String password) {
        String p = password == null ? "" : password;
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("At least " + MIN_LENGTH + " characters", p.length() >= MIN_LENGTH));
        rules.add(new Rule("No more than " + MAX_LENGTH + " characters", p.length() <= MAX_LENGTH));
        rules.add(new Rule("An uppercase letter (A to Z)", p.chars().anyMatch(Character::isUpperCase)));
        rules.add(new Rule("A lowercase letter (a to z)", p.chars().anyMatch(Character::isLowerCase)));
        rules.add(new Rule("A number (0 to 9)", p.chars().anyMatch(c -> c >= '0' && c <= '9')));
        rules.add(new Rule("A symbol, such as ! @ # $ %",
                p.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c))));
        rules.add(new Rule("Not a common password, such as Password1", !isCommon(p)));
        rules.add(new Rule("No simple pattern, such as aaa, abc or 123", !hasSimplePattern(p)));
        return rules;
    }

    /** Throws if the password fails any requirement. The message names what is missing. */
    public static void check(String password) throws InputParser.InvalidInputException {
        List<String> missing = new ArrayList<>();
        for (Rule rule : rules(password)) {
            if (!rule.met) {
                missing.add(rule.text);
            }
        }
        if (!missing.isEmpty()) {
            throw new InputParser.InvalidInputException("The password does not meet these requirements: "
                    + String.join("; ", missing) + ".");
        }
    }

    /** A plain word for how strong the password is, shown as the user types. Empty when nothing is typed. */
    public static String strengthLabel(String password) {
        if (password == null || password.isEmpty()) {
            return "";
        }
        List<Rule> rules = rules(password);
        long met = rules.stream().filter(r -> r.met).count();
        if (met < rules.size()) {
            return met * 2 <= rules.size() ? "Weak" : "Fair";
        }
        return password.length() >= 16 ? "Very strong" : "Strong";
    }

    private static boolean isCommon(String password) {
        String letters = password.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return COMMON.contains(letters);
    }

    /** Three of the same character in a row, or three characters counting up or down (abc, 321). */
    private static boolean hasSimplePattern(String password) {
        String t = password.toLowerCase(Locale.ROOT);
        for (int i = 0; i + 2 < t.length(); i++) {
            char a = t.charAt(i);
            char b = t.charAt(i + 1);
            char c = t.charAt(i + 2);
            if (a == b && b == c) {
                return true;
            }
            if (b - a == 1 && c - b == 1 || a - b == 1 && b - c == 1) {
                return true;
            }
        }
        return false;
    }
}
