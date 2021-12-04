package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.Strings;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.function.BiConsumer;

import static alpha.nomagichttp.util.Strings.containsIgnoreCase;
import static java.util.Objects.requireNonNull;

/**
 * An extension API on top of a JDK-provided {@code HttpHeaders} delegate.<p>
 * 
 * Subtypes add methods that lookup and/or parse named and message-specific
 * headers, e.g. {@link ContentHeaders#contentType()} and {@link
 * Request.Headers#accept()}.<p>
 * 
 * Header key- and values when stored will have leading and trailing whitespace
 * removed. They will also maintain letter capitalization when stored and
 * consequently when retrieved, but are case-insensitive when querying.<p>
 * 
 * The order of header keys is not specified (see {@link HttpHeaders}) nor is
 * the order significant (<a href="https://tools.ietf.org/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>)
 * .<p>
 * 
 * The BetterHeaders implementation will utilize a cache when deemed necessary.
 * This is certainly true for all methods named after a header which it parses
 * (so, application may assume there is no performance impact for repetitive
 * calls).<p>
 * 
 * The implementation is thread-safe and non-blocking. It's {@code hashCode} and
 * {@code toString} methods delegate to the JDK delegate, which in turn,
 * delegates to the underlying map. {@code equals} computes equality based on
 * the runtime type of this instance and the delegate's map.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see HttpConstants.HeaderKey
 */
public interface BetterHeaders
{
    /**
     * Returns the HTTP headers.<p>
     * 
     * Almost all methods in the JDK class have an equivalent [and better]
     * method in this interface. The one exception is {@link HttpHeaders#map()}.
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
     * Combine and tokenize all values for the given header name.<p>
     * 
     * All values of the header will be combined, then split using a comma, then
     * stripped. Empty tokens ignored.<p>
     * 
     * This method is useful when extracting potentially repeated fields defined
     * as a comma-separated list of tokens.<p>
     * 
     * Given these headers:
     * <pre>
     *     Trailer: first
     *     Trailer: second, ,  third
     * </pre>
     * The result is:
     * <pre>
     *     ["first", "second", "third]
     * </pre>
     * 
     * @param name of header
     * @return tokens (list is unmodifiable, {@link RandomAccess}, never {@code null})
     * @throws NullPointerException if {@code header} is {@code null}
     * @see #allTokensKeepQuotes(String)
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.6">RFC 7230 §3.2.6</a>
     */
    List<String> allTokens(String name);
    
    /**
     * Does what {@link #allTokens(String)} do, except it does not split on a
     * comma found within a quoted string.
     * 
     * Given these headers:
     * <pre>
     *     Accept: text/plain
     *     Accept: text/something;param="foo,bar"
     * </pre>
     * The result is:
     * <pre>
     *     ["text/plain", "text/something;param="foo,bar""]
     * </pre>
     * 
     * Field comments are kept as-is.
     * 
     * @param name header name
     * @return tokens (list is unmodifiable, {@link RandomAccess}, never {@code null})
     * @throws NullPointerException if {@code header} is {@code null}
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.6">RFC 7230 §3.2.6</a>
     * @see Strings#split(CharSequence, char, char) 
     */
    List<String> allTokensKeepQuotes(String name);
    
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