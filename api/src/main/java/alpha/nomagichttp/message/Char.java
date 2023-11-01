package alpha.nomagichttp.message;

import java.util.Map;

import static java.util.Arrays.stream;
import static java.util.Locale.ROOT;
import static java.util.stream.Collectors.toMap;

/**
 * Utility enumeration of char values relevant to the HTTP protocol.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public enum Char {
    /** The tab character {@code \t}. */
    TAB             ('\t', "\\t"),
    /** The backspace character {@code \b}. */
    BACKSPACE       ('\b', "\\b"),
    /** The line feed character {@code \n}. */
    LINE_FEED       ('\n', "\\n"),
    /** The carriage return character "{@code \r}. */
    CARRIAGE_RETURN ('\r', "\\r"),
    /** The form feed character {@code \f}. */
    FORM_FEED       ('\f', "\\f"),
    /** The single quote character {@code \'}. */
    SINGLE_QUOTE    ('\'', "\\'"),
    /** The double quote character {@code \"}. */
    DOUBLE_QUOTE    ('\"', "\\\""),
    /** The backslash character {@code \\}. */
    BACKSLASH       ('\\', "\\\\");
    
    /**
     * {@return a char debug String}<p>
     * 
     * Here's an example result given the the char '\n':
     * <pre>
     *   (hex:0xA, decimal:10, char:"\n")
     * </pre>
     * 
     * @param c character to debug
     */
    public static String toDebugString(char c) {
        final String hex = Integer.toHexString(c).toUpperCase(ROOT),
                     dec = Integer.toString(c),
                     chr = Char.toString(c);
        
        return "(hex:0x" + hex + ", decimal:" + dec + ", char:\"" + chr + "\")";
    }
    
    private final char c;
    private final String s;
    
    /**
     * Constructs a {@code Char}.
     * 
     * @param c the backing char
     * @param s what an escaped version of the char looks like
     */
    Char(char c, String s) {
        this.c = c;
        this.s = s;
    }
    
    private char charValue() {
        return c;
    }
    
    private String stringValue() {
        return s;
    }
    
    private static final Map<Character, String> INDEX = stream(Char.values())
            .collect(toMap(Char::charValue, Char::stringValue));
    
    private static String toString(char c) {
        return INDEX.getOrDefault(c, String.valueOf(c));
    }
}