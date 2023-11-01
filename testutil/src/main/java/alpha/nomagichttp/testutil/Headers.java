package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.ContentHeaders;
import alpha.nomagichttp.message.DefaultContentHeaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.lang.String.CASE_INSENSITIVE_ORDER;

/**
 * Utility methods for constructing headers.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Headers
{
    private Headers() {
        // Empty
    }
    
    /**
     * Returns a new instance of the default implementation containing the
     * specified pairs.<p>
     * 
     * The specified header values will <i>not</i> have trailing whitespace
     * stripped.
     * 
     * @param nameValuePairs header entries
     * 
     * @return new content headers
     * 
     * @throws NullPointerException
     *             if {@code nameValuePairs} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code nameValuePairs.length} is not even
     */
    public static ContentHeaders contentHeaders(String... nameValuePairs) {
        return new DefaultContentHeaders(linkedHashMap(nameValuePairs), false);
    }
    
    /**
     * {@return a new {@code LinkedHashMap} containing the specified pairs}<p>
     * 
     * The returned map will obviously retain the provided order of headers, but
     * does not provide a case-insensitive {@code equals} method for the header
     * names.
     * 
     * @param nameValuePairs header entries
     * 
     * @throws NullPointerException
     *             if {@code nameValuePairs} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code nameValuePairs.length} is not even
     * 
     * @see #treeMap(String...) 
     */
    public static LinkedHashMap<String, List<String>> linkedHashMap(
            String... nameValuePairs) {
        return putHeaders(new LinkedHashMap<>(), nameValuePairs);
    }
    
    /**
     * {@return a new linked hash map containing the specified headers}<p>
     * 
     * The returned map will retain the order of headers, but does not provide a
     * case-insensitive {@code equals} method for the header names.
     * 
     * @param headers to copy
     * 
     * @throws NullPointerException
     *             if {@code headers} is {@code null}
     * 
     * @see #treeMap(BetterHeaders)
     */
    public static LinkedHashMap<String, List<String>> linkedHashMap(
            BetterHeaders headers) {
        return copy(new LinkedHashMap<>(), headers);
    }
    
    /**
     * {@return a new tree map containing the specified pairs}<p>
     * 
     * The returned map will be equal to another {@code TreeMap}, as long as the
     * other map contains the same set of header names (case-insensitive) and
     * values (case-sensitive).<p>
     * 
     * The iteration order of the returned map is defined by
     * {@link String#compareToIgnoreCase(String)}.
     * 
     * @param nameValuePairs header entries
     * 
     * @throws NullPointerException
     *             if {@code headers} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code nameValuePairs.length} is not even
     * 
     * @see #linkedHashMap(String...) 
     */
    public static TreeMap<String, List<String>> treeMap(
            String... nameValuePairs) {
        return putHeaders(
                new TreeMap<>(CASE_INSENSITIVE_ORDER),
                nameValuePairs);
    }
    
    /**
     * {@return a new tree map containing the specified headers}<p>
     * 
     * The returned map will be equal to another {@code TreeMap}, as long as the
     * other map contains the same set of header names (case-insensitive) and
     * values (case-sensitive).<p>
     * 
     * The iteration order of the returned map is defined by
     * {@link String#compareToIgnoreCase(String)}.
     * 
     * @param headers to copy
     * 
     * @throws NullPointerException
     *             if {@code headers} is {@code null}
     * 
     * @see #linkedHashMap(String...) 
     */
    public static TreeMap<String, List<String>> treeMap(BetterHeaders headers) {
        return copy(new TreeMap<>(CASE_INSENSITIVE_ORDER), headers);
    }
    
    private static <M extends Map<String, List<String>>> M putHeaders(
            M destination, String... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Please provide an even number of pairs.");
        }
        for (int i = 0; i < nameValuePairs.length - 1; i += 2) {
            String k = nameValuePairs[i],
                   v = nameValuePairs[i + 1];
            destination.computeIfAbsent(k, k0 -> new ArrayList<>(1)).add(v);
        }
        return destination;
    }
    
    private static <M extends Map<String, List<String>>> M copy(
            M destination, BetterHeaders source) {
        source.forEach(destination::put);
        return destination;
    }
}