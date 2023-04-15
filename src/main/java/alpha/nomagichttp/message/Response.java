package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.util.ByteBufferIterables;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

import static alpha.nomagichttp.HttpConstants.ReasonPhrase;
import static alpha.nomagichttp.HttpConstants.StatusCode;

/**
 * A status line, followed by optional headers, body and trailers.<p>
 * 
 * Can be built using a {@link Response.Builder}:
 * 
 * <pre>{@code
 *   // May use HttpConstants.StatusCode/ReasonPhrase instead of int and "string"
 *   Response r = Response.builder(204, "No Content")
 *                        .header("My-Header", "value")
 *                        .build();
 * }</pre>
 * 
 * The {@code Response} object is immutable, but the builder that built the
 * response can be retrieved and used to create new response derivatives, which
 * effectively makes any {@code Response} instance a cacheable template, and the
 * {@link Responses} class can be considered a repository of commonly used
 * status lines.<p>
 * 
 * This example is equivalent to the previous:
 * 
 * <pre>{@code
 *   Response r = Responses.noContent().toBuilder()
 *                         .header("My-Header", "value")
 *                         .build();
 * }</pre>
 * 
 * {@code Responses} in combination with {@link ByteBufferIterables} for
 * creating bodies should be an adequate API for most use cases.
 * 
 * <pre>{@code
 *   ByteBufferIterable body = ByteBufferIterables.just(...);
 *   Response r = Responses.ok(body); // 200 OK
 * }</pre>
 * 
 * The content of the response head (status line and headers) will be written
 * to the client verbatim (casing will be preserved).<p>
 * 
 * The {@code Response} implementation is thread-safe, but a non-empty response
 * body does not have to be. All responses created by the NoMagicHTTP library
 * are fully thread-safe (response and body), and can be cached globally and
 * reused, but only as long as <i>the application</i> itself did not explicitly
 * provide a response body that is not thread-safe.<p>
 * 
 * The implementation does not necessarily implement {@code hashCode} and
 * {@code equals}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Response.Builder
 * @see RequestHandler
 * @see HttpServer
 */
public interface Response extends HeaderHolder
{
    /**
     * Returns a {@code Response} builder.<p>
     * 
     * By default, the reason phrase will be "Unknown" if not set in the
     * returned builder.
     * 
     * @param statusCode response status code
     * @return a builder (doesn't have to be a new instance)
     * @see #statusCode()
     */
    static Builder builder(int statusCode) {
        return Responses.status(statusCode).toBuilder();
    }
    
    /**
     * Returns a {@code Response} builder.
     * 
     * @param statusCode response status code
     * @param reasonPhrase response reason phrase
     * 
     * @return a builder (doesn't have to be a new instance)
     * 
     * @see #statusCode()
     * @see #reasonPhrase()
     * 
     * @throws NullPointerException
     *             if {@code reasonPhrase} is {@code null}
     */
    static Builder builder(int statusCode, String reasonPhrase) {
        return Responses.status(statusCode, reasonPhrase).toBuilder();
    }
    
    /**
     * Returns the status code.<p>
     * 
     * As far as the server is concerned, the returned value may be any integer
     * value, but should be conforming to the HTTP protocol.
     * 
     * @return the status code
     * 
     * @see HttpConstants.StatusCode
     */
    int statusCode();
    
    /**
     * Returns the reason phrase.<p>
     * 
     * The returned value may be {@code null} or an empty string, in which case
     * no reason phrase will be added to the status line.<p>
     * 
     * The default implementation will return "Unknown" if not set.
     * 
     * @return the reason phrase
     * 
     * @see HttpConstants.ReasonPhrase
     */
    String reasonPhrase();
    
    /**
     * Returns the message body (possibly empty).
     * 
     * @return the message body
     */
    ResourceByteBufferIterable body();
    
    /**
     * Returns trailers.<p>
     * 
     * A response that wishes to send trailers must set the
     * {@value HttpConstants.HeaderName#TRAILER} header. The value(s) should
     * precisely match the trailer name(s). The server will not even call this
     * method if the header has not been set. Otherwise, this method will be
     * called exactly once after the body has been written, more specifically;
     * after {@code ByteBufferIterable.close} has been called. The method must
     * then return non-empty trailers. The server does not verify that the
     * actual trailers sent is correctly enumerated in the "Trailer" header.
     * 
     * @apiNote
     * Although
     * <a href="https://www.rfc-editor.org/rfc/rfc7230#section-4.4">RFC 7230 §4.4</a>
     * specifies the "Trailer" header as optional (unfortunately), the server
     * must know in advance before sending the body whether trailers will be
     * present, as the presence of trailers may require the server to apply
     * chunked encoding. Thus, why the trailer is upgraded to be required.
     * 
     * @implSpec
     * The default implementation returns {@code null}.
     * 
     * @return trailers (possibly {@code null})
     * 
     * @see Request#trailers() 
     */
    default LinkedHashMap<String, List<String>> trailers() {
        return null;
    }
    
    /**
     * Returns {@code true} if the status-code is 1XX (Informational).
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *   return StatusCode.{@link StatusCode#isInformational(int)
     *       isInformational}(this.statusCode());
     * </pre>
     * 
     * @return see JavaDoc
     */
    default boolean isInformational() {
        return StatusCode.isInformational(statusCode());
    }
    
    /**
     * Returns {@code true} if the status-code is 2XX (Successful).
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *   return StatusCode.{@link StatusCode#isSuccessful(int)
     *       isInformational}(this.statusCode());
     * </pre>
     * 
     * @return see JavaDoc
     */
    default boolean isSuccessful() {
        return StatusCode.isSuccessful(statusCode());
    }
    
    /**
     * Returns {@code true} if the status-code is not 1XX (Informational).
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *   return StatusCode.{@link StatusCode#isFinal(int)
     *       isFinal}(this.statusCode());
     * </pre>
     * 
     * @return see JavaDoc
     */
    default boolean isFinal() {
        return StatusCode.isFinal(statusCode());
    }
    
    /**
     * Returns the builder instance that built this response.<p>
     * 
     * The builder may be used for further response templating.
     * 
     * @return the builder instance that built this response
     */
    Builder toBuilder();
    
    /**
     * Builder of a {@link Response}.<p>
     * 
     * The builder is immutable. All builder-returning methods return a new
     * instance representing the new state. The builder can be used as a
     * template to derive new responses. {@link Response#toBuilder()} returns
     * the builder that built the response which effectively makes any response
     * function as a template.<p>
     * 
     * Status code is the only required field.<p>
     * 
     * Leading and trailing whitespace in header names and values, is not
     * accepted (
     * <a href="https://datatracker.ietf.org/doc/html/rfc7230/#section-3.2.4">RFC §3.2.4. Field Parsing</a>
     * ). Header names are not accepted to be empty, values may be empty. The
     * content is generally not validated. The application should only use
     * visible US ASCII characters, and never write a header name with
     * whitespace in it (
     * <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2">RFC 7230 §3.2</a>
     * ).<p>
     * 
     * Adding values to the same header name replicates the header across
     * multiple rows in the response. It does <strong>not</strong> join the
     * values on the same row. If this is desired, first join multiple values
     * and then pass it to the builder as one, or consider using
     * {@link #appendHeaderToken(String, String)}.<p>
     * 
     * Header name and values will be written on the wire verbatim (unmodified;
     * casing preserved). However, {@link #build()} throws an
     * {@link IllegalStateException} if a header name has been duplicated using
     * different casing.<p>
     * 
     * Header order for different names is not significant (see note). The
     * addition order will be preserved on the wire, except for a duplicated
     * name whose entry will be inserted after the last occurrence (i.e., they
     * are grouped).<p>
     * 
     * Although the builder will strive to fail-fast, some message variants are
     * illegal depending on a future context. That is to say, the
     * {@code Response} may build just fine but cause an exception to be thrown
     * at a later point. For example, responding a response body to a
     * {@code HEAD} request.<p>
     * 
     * The implementation is thread-safe and non-blocking.<p>
     * 
     * The implementation does not necessarily implement {@code hashCode} and
     * {@code equals}.
     * 
     * @apiNote
     * Order may be significant for the same header name. For example, the
     * following cookie will end up storing the value "bar":
     * <pre>
     *   Set-Cookie: whatever=foo
     *   Set-Cookie: whatever=bar
     * </pre>
     * 
     * Although the order for different names is not significant, "it is a good
     * practice to send header fields that contain control data first" (
     * <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2. Field Order</a>
     * ). Examples of "control data" includes "Age", "Cache-Control", "Expires",
     * et cetera (
     * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-7.1">RFC 7231 §7.1. Control Data</a>
     * ).
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     * 
     * @see HttpConstants.HeaderName
     */
    interface Builder
    {
        /**
         * Sets a status code.
         * 
         * @param statusCode value (any integer value)
         * 
         * @return a new builder representing the new state
         * 
         * @see StatusCode
         */
        Builder statusCode(int statusCode);
        
        /**
         * Sets a reason phrase.
         * 
         * @param reasonPhrase value (any non-null string)
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if {@code reasonPhrase} is {@code null}
         * 
         * @see ReasonPhrase
         */
        Builder reasonPhrase(String reasonPhrase);
        
        /**
         * Sets a header.<p>
         * 
         * This method overwrites all previously set values for the given name
         * (case-sensitive).
         * 
         * @param name of header
         * @param value of header
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if any argument is {@code null}
         * @throws IllegalArgumentException
         *             if any argument has leading or trailing whitespace
         * @throws IllegalArgumentException
         *             if {@code name} is empty
         */
        Builder header(String name, String value);
        
        /**
         * Removes <i>all</i> occurrences of a header.<p>
         * 
         * This method operates without regard to casing.
         * 
         * @param name of the header
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *              if {@code name} is {@code null}
         * @throws IllegalArgumentException
         *             if {@code name} has leading or trailing whitespace
         * @throws IllegalArgumentException
         *             if {@code name} is empty
         */
        Builder removeHeader(String name);
        
        /**
         * Removes <i>all</i> occurrences of the given header value.<p>
         * 
         * If the header only has one value — which is removed — then the header
         * is also removed.<p>
         * 
         * This method operates without regard to casing.
         * 
         * @param name of the header
         * @param value to remove
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if any argument is {@code null}
         * @throws IllegalArgumentException
         *             if any argument has leading or trailing whitespace
         * @throws IllegalArgumentException
         *             if {@code name} is empty
         */
        Builder removeHeaderValue(String name, String value);
        
        /**
         * Adds a header to this response.<p>
         * 
         * If the header is already present, then it will be repeated in the
         * response.
         * 
         * @param name of the header
         * @param value of the header
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if any argument is {@code null}
         * @throws IllegalArgumentException
         *             if any argument has leading or trailing whitespace
         * @throws IllegalArgumentException
         *             if {@code name} is empty
         */
        Builder addHeader(String name, String value);
        
        /**
         * Adds header(s) to this response.<p>
         * 
         * This method is equivalent to calling
         * {@link #addHeader(String, String) addHeader}, for each given pair.<p>
         * 
         * Iterating the {@code String[]} must alternate between header-names
         * and values.<p>
         * 
         * The implementation is free to keep a reference to the given
         * {@code String[]} for future reads. The results are undefined if the
         * application modifies the array after having called this method.
         * 
         * @param name of header
         * @param value of header
         * @param morePairs of headers
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if any argument or array element is {@code null}
         * @throws IllegalArgumentException
         *             if {@code morePairs.length} is odd
         * @throws IllegalArgumentException
         *             if any given string has leading or trailing whitespace
         * @throws IllegalArgumentException
         *             if {@code name} is empty
         */
        Builder addHeaders(String name, String value, String... morePairs);
        
        /**
         * Appends the given {@code token} to the last non-empty header value,
         * if present, otherwise the method adds the token as a new header
         * value.<p>
         * 
         * If this method appends the token to a present, non-empty header
         * value, the separator used will be ", ". The given {@code token}
         * should not begin with a comma, and the header field should be defined
         * as a comma-separated list.<p>
         * 
         * This method operates without regard to casing.
         * 
         * @param name of the header
         * @param token to append
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if any argument is {@code null}
         * @throws IllegalArgumentException
         *             if any argument has leading or trailing whitespace
         * @throws IllegalArgumentException
         *             if any argument is empty
         */
        Builder appendHeaderToken(String name, String token);
        
        /**
         * Sets a message body.<p>
         * 
         * The application should always set the Content-Type header as the
         * server can not know this information. The header is usually
         * implicitly specified and set when using factories in
         * {@link Responses}.<p>
         * 
         * Generally, the server will copy and paste the body's
         * {@link ResourceByteBufferIterable#length() length} to the response
         * header Content-Length, and so, the application should only set this
         * header for exceptional cases (
         * <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.2">RFC 7230 §3.3.2</a>
         * ).<p>
         * 
         * If the response is used only once, then the body does not need to be
         * regenerative (see JavaDoc of {@link ResourceByteBufferIterable}). If
         * the response is cached and used many times, then the body should be
         * regenerative, at the very least thread-safe.<p>
         * 
         * To remove an already set body, it's as easy as specifying an empty
         * body to this method, for example, using
         * {@link ByteBufferIterables#empty()}.
         * 
         * @param body content
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if {@code body} is {@code null}
         */
        Builder body(ResourceByteBufferIterable body);
        
        /**
         * Add response trailers.<p>
         * 
         * The given supplier will be used as a delegate implementation for
         * {@link Response#trailers()}.<p>
         * 
         * The application must populate the HTTP header "Trailer" with the
         * names of the trailers that will be sent.<p>
         * 
         * The application should only send trailers if the client has indicated
         * that they are accepted (
         * <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-4.3">RFC 7230 $4.3</a>
         * ).
         * 
         * <pre>
         *   boolean accepted = request.headers().contains("TE", "trailers");
         * </pre>
         * 
         * If the request failed to parse, or it parsed but the client specified
         * an HTTP version older than 1.1, then the trailers will be silently
         * discarded. If the request is available, then the client's version can
         * be queried using {@link Request#httpVersion()}. The server can be
         * configured to not accept connections from old clients using
         * {@link Config#minHttpVersion()}.<p>
         * 
         * Trailers will be written out on the wire in the same way headers are;
         * order and casing is preserved. Although this builder requires that
         * header names- and values have no leading or trailing whitespace, the
         * same validation does not happen for the given trailers. Nonetheless,
         * the application should ensure there is no such whitespace in the
         * given strings.<p>
         * 
         * The application must not modify the {@code Map} or its values after
         * it has been supplied to the server.
         * 
         * @param trailers called after the body has been iterated
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if {@code trailers} is {@code null}
         * 
         * @see Request#trailers()
         * @see Config#minHttpVersion() 
         */
        Builder addTrailers(Supplier<LinkedHashMap<String, List<String>>> trailers);
        
        /**
         * Remove a previously set trailers' supplier, if present.
         * 
         * @return a new builder representing the new state
         */
        Builder removeTrailers();
        
        /**
         * Builds the response.<p>
         * 
         * This method returns a new response object on each call.
         * 
         * @return a response
         * 
         * @throws IllegalStateException
         *             if a header name is repeated using different casing
         * @throws IllegalStateException
         *             if status code is 1XX (Informational) and header {@code
         *             Connection: close} is set (
         *             <a href="https://tools.ietf.org/html/rfc7230#section-6.1">RFC 7231 §6.1</a>)
         * @throws IllegalResponseBodyException
         *             if the body is not knowingly
         *             {@link ResourceByteBufferIterable#isEmpty() empty},
         *             and the status code is one of
         *             1XX (Informational), 204 (No Content), 304 (Not Modified)
         */
        Response build();
    }
}