package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.util.ByteBufferIterables;
import alpha.nomagichttp.util.Headers;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
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
 * to the client verbatim/unaltered; i.e. casing and white space will be
 * preserved.<p>
 * 
 * Bear in mind that the response header Content-Length (case-insensitive)
 * should be set by the application only for exceptional cases (
 * <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.2">RFC 7230 §3.3.2</a>
 * ). Otherwise, the server will take care of copy and pasting the body's
 * {@link ResourceByteBufferIterable#length() length} to said header.<p>
 * 
 * The application should always set response containing a body must set the Content-Type header (as
 * the server can not know this information). These headers are not a concern
 * when one is building responses using the factories in {@link Responses} or
 * using the {@link Response.Builder}.<p>
 * 
 * The implementation is fully thread-safe and can be freely cached.<p>
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
     * Returns HTTP headers as they are written on the wire.<p>
     * 
     * The default implementation adheres to the contract as defined in JavaDoc
     * of {@link Response}. A custom implementation is free to change this.
     * 
     * @return HTTP headers
     */
    Iterable<String> headersForWriting();
    
    /**
     * Returns the message body (possibly empty).
     * 
     * @return the message body
     */
    ResourceByteBufferIterable body();
    
    /**
     * Returns trailers.<p>
     * 
     * A response that wishes to send trailers must set the "Trailer" header.
     * The value(s) should precisely match the trailer name(s). The server will
     * not even call this method if the "Trailer" header is not set. Otherwise,
     * this method will be called exactly once after the body has been written,
     * more specifically; after {@code ByteBufferIterable.close} has been
     * called. The method must then return non-empty trailers. The server does
     * not verify that the actual trailers sent was correctly enumerated in the
     * "Trailer" header.<p>
     * 
     * @apiNote
     * Although
     * <a href="https://www.rfc-editor.org/rfc/rfc7230#section-4.4">RFC 7230 §4.4</a>
     * specifies the "Trailer" header as optional (unfortunately), the server
     * must know in advance before sending the body whether trailers will be
     * present, as trailers may require the server to apply chunked encoding.
     * Thus, why the trailer is upgraded to be required.
     * 
     * @implSpec
     * The default implementation returns {@code null}.
     * 
     * @return trailers (possibly {@code null})
     * @see Request#trailers() 
     */
    default HttpHeaders trailers() {
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
     * All the remaining JavaDoc related to headers is true for the default
     * builder implementation building the default response implementation.<p>
     * 
     * The content of header names and values are generally not validated. The
     * application must not write invalid data such as a header name with
     * whitespace in it (
     * <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.2">RFC 7230 §3.2</a>
     * ). Header values can be empty.<p>
     * 
     * Adding many values to the same header name replicates the header across
     * multiple rows in the response. It does <strong>not</strong> join the
     * values on the same row. If this is desired, first join multiple values
     * and then pass it to the builder as one.<p>
     * 
     * Header order is not significant, but the addition order will be preserved
     * on the wire except for duplicated names which will be grouped together
     * and inserted at the occurrence of the first.<p>
     * 
     * Although the builder will strive to fail-fast, some message variants are
     * illegal depending on future context. They may build just fine but cause
     * an exception to be thrown at a later point. For example responding a
     * response with a body to a {@code HEAD} request.<p>
     * 
     * The implementation is thread-safe and non-blocking.<p>
     * 
     * The implementation does not necessarily implement {@code hashCode} and
     * {@code equals}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Builder
    {
        /**
         * Sets a status code.
         * 
         * @param  statusCode value (any integer value)
         * @return a new builder representing the new state
         * @see    StatusCode
         */
        Builder statusCode(int statusCode);
        
        /**
         * Sets a reason phrase.
         * 
         * @param  reasonPhrase value (any non-null string)
         * @throws NullPointerException if {@code reasonPhrase} is {@code null}
         * @return a new builder representing the new state
         * @see    ReasonPhrase
         */
        Builder reasonPhrase(String reasonPhrase);
        
        /**
         * Sets a header.<p>
         * 
         * This method overwrites all previously set values for the given name
         * (case-sensitive).
         * 
         * @param  name of header
         * @param  value of header
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if any argument is {@code null}
         * 
         * @see HttpConstants.HeaderName
         */
        Builder header(String name, String value);
        
        /**
         * Removes all occurrences of a header.<p>
         * 
         * This method operates without regard to casing.
         * 
         * @param  name of the header
         * @return a new builder representing the new state
         * @throws NullPointerException if {@code name} is {@code null}
         */
        Builder removeHeader(String name);
        
        /**
         * Removes all occurrences of given a header value.<p>
         * 
         * This method operates without regard to casing for both header name
         * and value.<p>
         * 
         * If there are no mapped values left after the operation, the header
         * will also be removed.
         * 
         * @param  name of the header
         * @param  value predicate
         * @return a new builder representing the new state
         * @throws NullPointerException if any argument is {@code null}
         */
        Builder removeHeaderValue(String name, String value);
        
        /**
         * Adds a header to this response.<p>
         * 
         * If the header is already present (case-sensitive) then it will be
         * repeated in the response.
         * 
         * @param name of the header
         * @param value of the header
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if any argument or is {@code null}
         * @throws IllegalArgumentException
         *             if {@code name} equals "Content-length"
         *             (case insensitive)
         * 
         * @see HttpConstants.HeaderName
         */
        Builder addHeader(String name, String value);
        
        /**
         * Adds header(s) to this response.<p>
         * 
         * Iterating the {@code String[]} must alternate between header-names
         * and values. To add several values to the same name then the same
         * name must be supplied with each additional value.<p>
         * 
         * The results are undefined if the {@code String[]} is modified before
         * the response has been built.
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
         *             if a header name equals "Content-length"
         *             (case insensitive)
         * 
         * @see HttpConstants.HeaderName
         */
        Builder addHeaders(String name, String value, String... morePairs);
        
        /**
         * Adds all headers from the given headers object.
         * 
         * @implSpec
         * The default implementation is
         * <pre>
         *     return this.{@link #addHeaders(Map)
         *       addHeaders}(headers.{@link BetterHeaders#delegate()
         *         delegate}().{@link HttpHeaders#map() map}());
         * </pre>
         * 
         * The delegate's map does not provide a guarantee regarding the
         * ordering.
         * 
         * @param headers to add
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if {@code headers} is {@code null}
         * @throws IllegalArgumentException
         *             if a header name equals "Content-length"
         *             (case insensitive)
         * 
         * @see HttpConstants.HeaderName
         */
        default Builder addHeaders(BetterHeaders headers) { // TODO: Remove!
            return addHeaders(headers.delegate().map());
        }
        
        /**
         * Adds all headers from the given map.<p>
         * 
         * The order of the response headers will follow the iteration order of
         * the provided map.<p>
         * 
         * The specified {@code Map} (and its values) must be effectively
         * unmodifiable. Changes to the contents after this method returns will
         * have undefined application behavior.
         * 
         * @param headers to add
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if {@code headers} is {@code null}
         * @throws IllegalArgumentException
         *             if a header name equals "Content-length"
         *             (case insensitive)
         * 
         * @see HttpConstants.HeaderName
         */
        Builder addHeaders(Map<String, List<String>> headers);
        
        /**
         * Sets a message body.<p>
         * 
         * If the response is used only once, then the body does not need to be
         * regenerative (see JavaDoc of {@link ResourceByteBufferIterable}. If
         * the response is cached and used many times, then the body must be
         * regenerative.<p>
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
         * The application should populate the HTTP header "Trailer" with the
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
         * If the HTTP exchange is using a version less than 1.1, then the
         * trailers will be silently discarded.<p>
         * 
         * Trailers will be written out on the wire in almost the same way
         * headers are. The only exception is that the order is not defined.
         * 
         * @param trailers called after the body has been iterated
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if {@code trailers} is {@code null}
         * 
         * @see Request#trailers()
         * @see Headers
         * @see Config#rejectClientsUsingHTTP1_0() 
         */
        Builder addTrailers(Supplier<HttpHeaders> trailers);
        
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
         *             if a header name is duplicated using different casing
         * @throws IllegalStateException
         *             if a header name is empty (after trimming whitespaces)
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