package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;

import java.net.http.HttpHeaders;
import java.util.List;

import static alpha.nomagichttp.util.Strings.containsIgnoreCase;
import static java.util.Objects.requireNonNull;

/**
 * An API to access message headers.<p>
 * 
 * Header key and values when stored will have leading and trailing whitespaces
 * removed. They also maintain letter capitalization but are case insensitive
 * for methods querying headers.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface HeaderHolder {
    
    /**
     * Returns the HTTP headers.<p>
     * 
     * The order is not significant (
     * <a href="https://tools.ietf.org/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>
     * ).
     * 
     * @return the HTTP headers
     * 
     * @see HttpConstants.HeaderKey
     */
    HttpHeaders headers();
    
    /**
     * If a header is present, check if it contains a value substring.<p>
     * 
     * Suppose the server receives this request:
     * <pre>
     *   GET /where?q=now HTTP/1.1
     *   Host: www.example.com
     *   User-Agent: curl/7.68.0
     * </pre>
     * 
     * Returns true:
     * <pre>
     *   request.headerContains("user-agent", "cUrL");
     * </pre>
     * 
     * This method searches through repeated headers.<p>
     * 
     * This method returns {@code false} if the header is not present.
     * 
     * @implSpec
     * The default implementation uses {@link #headers()}.
     * 
     * @param headerKey header key filter
     * @param valueSubstring value substring to look for
     * 
     * @return {@code true} if found, otherwise {@code false}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    default boolean headerContains(String headerKey, String valueSubstring) {
        // NPE is unfortunately not documented in JDK
        return headers().allValues(requireNonNull(headerKey)).stream()
                .anyMatch(v -> containsIgnoreCase(v, valueSubstring));
    }
    
    /**
     * Returns {@code true} if the searched header is missing or all header
     * values are empty, otherwise {@code false}.
     * 
     * @implSpec
     * The default implementation uses {@link #headers()}.
     * 
     * @param key of header
     * 
     * @return {@code true} if the searched header is missing or all header
     *         values are empty, otherwise {@code false}
     * 
     * @throws NullPointerException if {@code key} is {@code null}
     */
    default boolean headerIsMissingOrEmpty(String key) {
        requireNonNull(key);
        List<String> vals = headers().allValues(key);
        if (vals.isEmpty()) {
            return true;
        }
        return vals.stream().allMatch(String::isEmpty);
    }
}