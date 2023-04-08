package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.Strings;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.RandomAccess;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * HTTP headers.<p>
 * 
 * This is a generic interface. Specialized subtypes declare methods that lookup
 * and/or parse named- and message-specific headers, e.g. {@link
 * ContentHeaders#contentType()} and {@link Request.Headers#accept()}.<p>
 * 
 * Retrieved header names and values will retain letter capitalization, just as
 * they were received — or, will be sent — on the wire. Header names and values
 * are case-insensitive when specified as arguments to querying methods declared
 * by this interface or an implementation of it.
 * 
 * <pre>
 *   BetterHeaders headers = // from "Foo: bar"
 *   headers.forEach(...) // name = Foo, value = bar
 *   headers.allTokens("FOO") // returns "bar"
 *   headers.contains("foo", "BAR") // true
 * </pre>
 * 
 * Header value(s) can be an empty string, which would indicate the occurrence
 * of a header, just being empty, e.g. "Foo: ".<p>
 * 
 * The iteration order of header keys, values and tokens, is equal to the
 * received (request) or transmission order (response), after grouping
 * (repeated headers are grouped into one {@code List} of values).<p>
 * 
 * The implementation is thread-safe and non-blocking.<p>
 * 
 * For performance, almost all methods that embeds a header name in the method
 * name, utilizes a cache, for example, {@link #hasConnectionClose()}, and
 * {@link ContentHeaders#contentType()} (the only exception is
 * {@link ContentHeaders#transferEncoding()}). All methods that does not embed
 * such a header name should be assumed to not utilize a cache. For example
 * {@link #contains(String, String)}. Repeated calls to low-level, non-cached
 * methods may be slower versus the high-level, cached counterpart.<p>
 * 
 * The implementation <i>does not</i> implement {@code hashCode} and
 * {@code equals}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpConstants.HeaderName
 */
// TODO: Rename to Headers
public interface BetterHeaders extends Iterable<Map.Entry<String, List<String>>>
{
    /**
     * If a header is present, returns {@code true}, otherwise {@code false}.<p>
     * 
     * The header value(s) has no effect on the result of this method.
     * 
     * @param headerName the header name
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code headerName} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code headerName} has leading or trailing whitespace
     */
    // TODO: Rename isKeyPresent/isValuePresent? Also rename isMissingOrEmpty > isNotPresentOrEmpty?
    boolean contains(String headerName);
    
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
     *   request.headers().contains("user-agent", "cUrL");
     *   request.headers().contains("User-Agent", "curl/7.68.0");
     * </pre>
     * 
     * This method searches through repeated headers.<p>
     * 
     * This method returns {@code false} if the header is not present.
     * 
     * @param headerName the header name
     * @param valueSubstring value substring to look for
     * 
     * @return {@code true} if the value is found, otherwise {@code false}
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws IllegalArgumentException
     *             if {@code headerName} has leading or trailing whitespace
     */
    boolean contains(String headerName, String valueSubstring);
    
    /**
     * Returns whether the {@value HttpConstants.HeaderName#CONNECTION} header
     * is present and contains the value substring "close".<p>
     * 
     * This method is equivalent to:
     * <pre>
     *   {@link #contains(String, String) contains}("Connection", "close")
     * </pre>
     * 
     * @return see JavaDoc
     */
    boolean hasConnectionClose();
    
    /**
     * Returns whether the {@value HttpConstants.HeaderName#TRANSFER_ENCODING}
     * header is present and contains the value "chunked".<p>
     * 
     * This method is equivalent to:
     * <pre>
     *   {@link #contains(String, String) contains}("Transfer-Encoding", "chunked")
     * </pre>
     * 
     * @return see JavaDoc
     */
    boolean hasTransferEncodingChunked();
    
    /**
     * Returns {@code true} if the given header is missing or all of its mapped
     * values are empty, otherwise {@code false}.<p>
     * 
     * A method semantically equivalent to "isMissing" is already provided:
     * <pre>
     *   boolean isMissing = !headers.{@link #contains(String) contains}("My-Fortune");
     * </pre>
     * 
     * @param headerName the header name
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code headerName} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code headerName} has leading or trailing whitespace
     */
    boolean isMissingOrEmpty(String headerName);
    
    /**
     * Returns the first value of the given header name.
     * 
     * @param headerName the header name
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code headerName} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code headerName} has leading or trailing whitespace
     */
    Optional<String> firstValue(String headerName);
    
    /**
     * Returns the first value of the given header name, as a {@code long}.
     * 
     * @param headerName the header name
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code headerName} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code headerName} has leading or trailing whitespace
     * @throws NumberFormatException
     *             if the value is present but does not parse as a {@code long}
     * @throws ArithmeticException
     *             if the value is present and overflows a {@code long}
     */
    OptionalLong firstValueAsLong(String headerName);
    
    /**
     * Returns a {@code List} of all values mapped to the given header name.<p>
     * 
     * An immutable and non-null {@code List} that implements
     * {@link RandomAccess}, is always returned, which may be empty if the
     * header is not present.
     * 
     * @param headerName the header name
     * 
     * @throws NullPointerException
     *             if {@code headerName} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code headerName} has leading or trailing whitespace
     * 
     * @return see JavaDoc
     */
    List<String> allValues(String headerName);
    
    /**
     * Tokenizes all values for the given header name into a flat mapped
     * stream.<p>
     * 
     * Values will be split using a comma as delimiter, then stripped,
     * then-empty tokens are discarded.<p>
     * 
     * This method is useful when extracting potentially repeated fields defined
     * as a comma-separated list of tokens.<p>
     * 
     * Given these headers:
     * <pre>
     *     Trailer: first
     *     Trailer: second, ,   third
     * </pre>
     * 
     * The result is:
     * <pre>
     *     "first", "second", "third"
     * </pre>
     * 
     * @param headerName the header name
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code headerName} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code headerName} has leading or trailing whitespace
     * 
     * @see #allTokensKeepQuotes(String)
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.6">RFC 7230 §3.2.6</a>
     */
    Stream<String> allTokens(String headerName);
    
    /**
     * Does what {@link #allTokens(String)} do, except this method does not
     * split on a comma found within a quoted string.<p>
     * 
     * Given these headers:
     * <pre>
     *     Accept: text/plain
     *     Accept: text/something;param="foo,bar"
     * </pre>
     * 
     * The result for header name "accept", is:
     * <pre>
     *     "text/plain", "text/something;param=\"foo,bar\""
     * </pre>
     * 
     * Field comments are kept as-is.
     * 
     * @param headerName the header name
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code headerName} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code headerName} has leading or trailing whitespace
     * 
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.6">RFC 7230 §3.2.6</a>
     * @see Strings#split(CharSequence, char, char) 
     */
    Stream<String> allTokensKeepQuotes(String headerName);
    
    /**
     * Performs the given action for each contained header.<p>
     * 
     * The action-feed {@code List} is immutable.
     * 
     * @param action to be performed for each entry
     * 
     * @throws NullPointerException
     *             if {@code action} is {@code null}
     */
    void forEach(BiConsumer<String, List<String>> action);
    
    /**
     * {@inheritDoc}<p>
     * 
     * The returned iterator does not support the {@code remove} operation.<p>
     * 
     * The iterated {@code Map.Entry}'s are immutable.
     * 
     * @return {@inheritDoc}
     */
    @Override
    Iterator<Map.Entry<String, List<String>>> iterator();
}