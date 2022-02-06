package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.Strings;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static alpha.nomagichttp.util.Strings.containsIgnoreCase;
import static java.util.Objects.requireNonNull;

/**
 * An extension API on top of a JDK-provided {@code HttpHeaders} delegate.<p>
 * 
 * This is a generic interface. Specialized subtypes add methods that lookup
 * and/or parse named- and message-specific headers, e.g. {@link
 * ContentHeaders#contentType()} and {@link Request.Headers#accept()}.<p>
 * 
 * Header names- and values will retain letter capitalization as received on the
 * wire but are case-insensitive when querying using operations provided by an
 * implementation of this interface.
 * 
 * <pre>
 *   BetterHeaders headers = // from "FOO: bar"
 *   headers.delegate().map() // name = FOO, value = bar
 *   headers.allTokens("foo") // returns "bar"
 *   headers.contain("foo", "BAR") // true
 * </pre>
 * 
 * Header name- and values will not contain leading and trailing whitespace. The
 * name will never contain whitespace. The name can not be empty, the value can
 * be empty.<p>
 * 
 * The order of header names is not specified (see {@link HttpHeaders}) nor is
 * the order significant (<a href="https://tools.ietf.org/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>)
 * .<p>
 * 
 * The implementation is thread-safe and non-blocking.<p>
 * 
 * The implementation will utilize a cache when deemed necessary. This is
 * certainly true for all methods named after a header which it parses (so,
 * application may assume there is no performance impact for repetitive
 * calls).<p>
 * 
 * The implementation behaves as a
 * <a href="https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/doc-files/ValueBased.html">value-based class</a>.
 * use of identity-sensitive operations (including reference equality ({@code
 * ==}), identity hash code, or synchronization) on instances of {@code
 * BetterHeaders} may have unpredictable results and should be avoided. The
 * {@code equals} method should be used for comparisons.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see HttpConstants.HeaderName
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
     * If a header is present, returns {@code true}, otherwise {@code false}.<p>
     * 
     * The mapped value(s) of the header has no effect. The value(s) may be
     * empty and this method would still return {@code true}, as long as the
     * header has been received.
     * 
     * @implSpec
     * The default implementation uses {@link #delegate()}.
     * 
     * @param headerName header name filter
     * @return {@code true} if header is present, otherwise {@code false}
     * @throws NullPointerException if a is {@code null} 
     */
    default boolean contains(String headerName) {
        // NPE is unfortunately not documented in JDK
        return delegate().map().containsKey(requireNonNull(headerName));
    }
    
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
     * @param headerName header name filter
     * @param valueSubstring value substring to look for
     * 
     * @return {@code true} if found, otherwise {@code false}
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    default boolean contains(String headerName, String valueSubstring) {
        return delegate().allValues(requireNonNull(headerName)).stream()
                .anyMatch(v -> containsIgnoreCase(v, valueSubstring));
    }
    
    /**
     * Returns {@code true} if the given header is missing or all of its mapped
     * values are empty, otherwise {@code false}.
     * 
     * @implSpec
     * The default implementation uses {@link #delegate()}.
     * 
     * @param name of header
     * 
     * @return {@code true} if the given header is missing or all of its mapped
     *         values are empty, otherwise {@code false}
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    default boolean isMissingOrEmpty(String name) {
        requireNonNull(name);
        List<String> vals = delegate().allValues(name);
        if (vals.isEmpty()) {
            return true;
        }
        return vals.stream().allMatch(String::isEmpty);
    }
    
    /**
     * Combine- and tokenize all values for the given header name.<p>
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
     * @return tokens
     * @throws NullPointerException if {@code header} is {@code null}
     * @see #allTokensKeepQuotes(String)
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.6">RFC 7230 §3.2.6</a>
     */
    Stream<String> allTokens(String name);
    
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
     * @return tokens
     * @throws NullPointerException if {@code header} is {@code null}
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.6">RFC 7230 §3.2.6</a>
     * @see Strings#split(CharSequence, char, char) 
     */
    Stream<String> allTokensKeepQuotes(String name);
    
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