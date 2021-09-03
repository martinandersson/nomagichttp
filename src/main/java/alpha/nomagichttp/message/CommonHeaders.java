package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.Headers;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiConsumer;

import static alpha.nomagichttp.util.Strings.containsIgnoreCase;
import static java.util.Objects.requireNonNull;

/**
 * Is an extension API on top of an underlying {@link HttpHeaders} instance,
 * retrievable using {@link #delegate()}.<p>
 * 
 * Header key and values when stored will have leading and trailing whitespace
 * removed. They will also maintain letter capitalization when stored and
 * consequently when retrieved, but are case insensitive when querying.<p>
 * 
 * The order of header entries (header keys) is not specified
 * (see {@link HttpHeaders}) nor is the order significant (<a
 * href="https://tools.ietf.org/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>)
 * .<p>
 * 
 * Methods that parse a specific/named header value (e.g.
 * {@link #contentType()}) from a string into another Java type generally caches
 * the result and so should be preferred over using the {@link Headers} util
 * class directly.<p>
 * 
 * The implementation is thread-safe and non-blocking.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see HttpConstants.HeaderKey
 */
public interface CommonHeaders
{
    /**
     * Returns the HTTP headers.
     * 
     * @return the HTTP headers (never {@code null})
     */
    HttpHeaders delegate();
    
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
     *   request.headers().contain("user-agent", "cUrL");
     * </pre>
     * 
     * This method searches through repeated headers.<p>
     * 
     * This method returns {@code false} if the header is not present.
     * 
     * @implSpec
     * The default implementation uses {@link #delegate()}.
     * 
     * @param headerKey header key filter
     * @param valueSubstring value substring to look for
     * 
     * @return {@code true} if found, otherwise {@code false}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    default boolean contain(String headerKey, String valueSubstring) {
        // NPE is unfortunately not documented in JDK
        return delegate().allValues(requireNonNull(headerKey)).stream()
                .anyMatch(v -> containsIgnoreCase(v, valueSubstring));
    }
    
    /**
     * Returns {@code true} if the given header is missing or all of its mapped
     * values are empty, otherwise {@code false}.
     * 
     * @implSpec
     * The default implementation uses {@link #delegate()}.
     * 
     * @param key of header
     * 
     * @return {@code true} if the given header is missing or all of its mapped
     *         values are empty, otherwise {@code false}
     * 
     * @throws NullPointerException if {@code key} is {@code null}
     */
    default boolean isMissingOrEmpty(String key) {
        requireNonNull(key);
        List<String> vals = delegate().allValues(key);
        if (vals.isEmpty()) {
            return true;
        }
        return vals.stream().allMatch(String::isEmpty);
    }
    
    /**
     * A shortcut for {@link Headers#contentType(HttpHeaders)}.
     * 
     * @return parsed value (never {@code null})
     * @throws BadHeaderException
     *           if headers has multiple Content-Type keys, or
     *           if parsing failed (cause set to {@link MediaTypeParseException})
     */
    Optional<MediaType> contentType();
    
    /**
     * A shortcut for {@link Headers#contentLength(HttpHeaders)}.
     * 
     * @return parsed value (never {@code null})
     * @throws BadHeaderException
     *             if header value can not be parsed, or
     *             the header has multiple Content-Length keys
     */
    OptionalLong contentLength();
    
    /**
     * Returns an {@link Optional} containing the first header string value of
     * the given named (and possibly multi-valued) header. If the header is not
     * present, then the returned {@code Optional} is empty.
     * 
     * @implSpec
     * The default implementation is
     * <pre>
     *     return this.{@link #delegate()
     *       delegate}().{@link HttpHeaders#firstValue(String)
     *         firstValue}({@link Objects#requireNonNull(Object)
     *           requireNonNull}(name));
     * </pre>
     * 
     * @param name the header name
     * @return an {@code Optional<String>} containing the first named header
     *         string value, if present
     * @throws NullPointerException if {@code name} is {@code null}
     */
    default Optional<String> firstValue(String name) {
        // NPE unfortunately not documented in JDK
        return delegate().firstValue(requireNonNull(name));
    }
    
    /**
     * Returns an {@link OptionalLong} containing the first header string value
     * of the named header field. If the header is not present, then the
     * Optional is empty. If the header is present but contains a value that
     * does not parse as a {@code Long} value, then an exception is thrown.
     *
     * @implSpec
     * The default implementation is
     * <pre>
     *     return this.{@link #delegate()
     *       delegate}().{@link HttpHeaders#firstValueAsLong(String)
     *         firstValueAsLong}({@link Objects#requireNonNull(Object)
     *           requireNonNull}(name));
     * </pre>
     * 
     * @param name the header name
     * @return  an {@code OptionalLong}
     * 
     * @throws NullPointerException
     *             if {@code name} is {@code null}
     * @throws NumberFormatException
     *             if a value is found, but does not parse as a Long
     */
    default OptionalLong firstValueAsLong(String name) {
        // NPE unfortunately not documented in JDK
        return delegate().firstValueAsLong(requireNonNull(name));
    }
    
    /**
     * Perform the given action for each HTTP header entry.
     * 
     * @implSpec
     * The default implementation is
     * <pre>
     *     this.{@link #delegate()
     *       delegate}().{@link HttpHeaders#map()
     *         map}().{@link Map#forEach(BiConsumer)
     *           forEach}(action)
     * </pre>
     * 
     * @param action to be performed for each entry
     * @throws NullPointerException if the specified action is null
     */
    default void forEach(BiConsumer<String, ? super List<String>> action) {
        delegate().map().forEach(action);
    }
}