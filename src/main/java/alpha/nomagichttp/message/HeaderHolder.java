package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.Headers;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Optional;

import static alpha.nomagichttp.util.Strings.containsIgnoreCase;
import static java.util.Objects.requireNonNull;

/**
 * An API to access message headers.<p>
 * 
 * Header key and values when stored will have leading and trailing whitespace
 * removed. They will also maintain letter capitalization when stored and
 * consequently when retrieved, but are case insensitive when querying.<p>
 * 
 * The order of header entries (header keys) is not specified
 * (see {@link HttpHeaders}) nor is the order significant (<a
 * href="https://tools.ietf.org/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>).<p>
 * 
 * The underlying {@link HttpHeaders} object is immutable and remains the same
 * throughout the life of the header holder. This enables "headerValXXX()"
 * methods implemented by the header holder to use a safe and fast internal
 * cache instead of parsing header values anew. These methods should generally
 * be preferred over using the class {@link Headers} directly.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface HeaderHolder {
    
    /**
     * Returns the HTTP headers.
     * 
     * @return the HTTP headers (never {@code null})
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
     * Returns {@code true} if the given header is missing or all of its mapped
     * values are empty, otherwise {@code false}.
     * 
     * @implSpec
     * The default implementation uses {@link #headers()}.
     * 
     * @param key of header
     * 
     * @return {@code true} if the given header is missing or all of its mapped
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
    
    /**
     * A caching shortcut for {@link Headers#contentType(HttpHeaders)}.
     * 
     * @return parsed value (never {@code null})
     * @throws BadHeaderException
     *           if headers has multiple Content-Type keys, or
     *           if parsing failed (cause set to {@link MediaTypeParseException})
     */
    Optional<MediaType> headerValContentType();
}