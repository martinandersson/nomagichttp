package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.util.BetterBodyPublishers;
import alpha.nomagichttp.util.Publishers;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.HttpConstants.ReasonPhrase;
import static alpha.nomagichttp.HttpConstants.StatusCode;
import static java.net.http.HttpRequest.BodyPublisher;

/**
 * A {@code Response} contains a status line, followed by optional headers and
 * body.<p>
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
 * The {@code Response} object is immutable, but the builder who built it can be
 * retrieved, effectively transforming any response object to a template. This
 * also renders the {@link Responses} class as a repository of commonly used
 * status lines. This example is equivalent to the previous:
 * 
 * <pre>{@code
 *   Response r = Responses.noContent().toBuilder()
 *                         .header("My-Header", "value")
 *                         .build();
 * }</pre>
 * 
 * {@code Responses} in combination with body factories in {@link
 * BetterBodyPublishers} ought to cover for the most common use cases.
 * 
 * <pre>{@code
 *   BodyPublisher body = BetterBodyPublishers.ofByteArray(...);
 *   Response r = Responses.ok(body); // 200 OK
 * }</pre>
 * 
 * The status line will be built by the server by joining the active HTTP
 * protocol version, status code and reason phrase. E.g. "HTTP/1.1 200 OK".<p>
 * 
 * The content of the response head (status line and headers) will be written
 * to the client verbatim/unaltered; i.e. casing will be preserved, yes, even
 * space characters. The head is encoded into bytes using {@link
 * StandardCharsets#US_ASCII US_ASCII} (UTF-8 is backwards compatible with
 * ASCII).<p>
 * 
 * When the headers are written on the wire, name and value will be concatenated
 * using a colon followed by a space (": "). Adding many values to the same
 * name replicates the header across multiple rows in the response. It does
 * <strong>not</strong> join the values on the same row. If this is desired,
 * first join multiple values and then pass it to the builder as one.<p>
 * 
 * Header order is not significant (see {@link ContentHeaders}), but - unless
 * documented differently - the response builder will preserve the addition
 * order on the wire (FIFO) except for duplicated names which will be grouped
 * together and inserted at the occurrence of the first value.<p>
 * 
 * The exact appearance of the headers on the wire can be customized by a custom
 * implementation of {@link #headersForWriting()}<p>
 * 
 * The {@code Response} object can safely be reused sequentially over time to
 * the same client. The response can also be shared concurrently to different
 * clients, assuming the {@linkplain Builder#body(Flow.Publisher) body
 * publisher} is thread-safe. If the publisher instance was retrieved using any
 * method provided by the NoMagicHTTP library (e.g. {@link
 * BetterBodyPublishers}), then it is fully thread-safe and non-blocking. All
 * responses created by {@link Responses} that does not accept the body
 * publisher as an argument uses a thread-safe body publisher under the hood.<p>
 * 
 * The {@code Response} implementation does not necessarily implement {@code
 * hashCode()} and {@code equals()}.
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
     * @return a builder (doesn't have to be a new instance)
     * @see #statusCode()
     * @see #reasonPhrase()
     * @throws NullPointerException if {@code reasonPhrase} is {@code null}
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
     * Returns the reason phrase.
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
     * Returns header lines as they are written on the wire out to client.<p>
     * 
     * The default implementation adheres to the contract as defined in JavaDoc
     * of {@link Response}. A custom implementation is free to change this.
     * 
     * @return the headers as they are written on the wire (unmodifiable)
     */
    Iterable<String> headersForWriting();
    
    /**
     * Returns the message body (possibly empty).
     * 
     * @return the message body (possibly empty)
     */
    Flow.Publisher<ByteBuffer> body();
    
    /**
     * Returns {@code true} if the status-code is 1XX (Informational), otherwise
     * {@code false}.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *   return StatusCode.{@link StatusCode#isInformational(int)
     *       isInformational}(this.statusCode());
     * </pre>
     * 
     * @return {@code true} if the status-code is 1XX (Informational),
     *         otherwise {@code false}
     */
    default boolean isInformational() {
        return StatusCode.isInformational(statusCode());
    }
    
    /**
     * Returns {@code true} if the status-code is not 1XX (Informational),
     * otherwise {@code false}.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *   return StatusCode.{@link StatusCode#isFinal(int)
     *       isFinal}(this.statusCode());
     * </pre>
     * 
     * @return {@code true} if the status-code is not 1XX (Informational),
     *         otherwise {@code false}
     */
    default boolean isFinal() {
        return StatusCode.isFinal(statusCode());
    }
    
    /**
     * Returns {@code true} if the body is assumed to be empty, otherwise
     * {@code false}.<p>
     * 
     * The body is assumed to be empty, if {@code this.body()} returns the same
     * object instance as {@link Publishers#empty()}, or it returns a
     * {@link HttpRequest.BodyPublisher} implementation with {@code
     * contentLength()} set to 0, or the response has a {@code Content-Length}
     * header set to 0 [in the future: and no chunked encoding].
     * 
     * @return {@code true} if the body is assumed to be empty,
     *         otherwise {@code false}
     */
    boolean isBodyEmpty();
    
    /**
     * Returns this response object boxed in an already completed stage.
     * 
     * @return this response object boxed in an already completed stage
     * 
     * @see HttpServer
     */
    CompletionStage<Response> completedStage();
    
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
     * The builder can be used as a template to modify per-response state. Each
     * method returns a new builder instance representing the new state. The API
     * should be used in a fluent style. There's generally no reason to save a
     * builder reference as the builder that built a response can be retrieved
     * using {@link Response#toBuilder()}<p>
     * 
     * Status code is the only required field. Please note that some message
     * variants may build just fine but {@linkplain HttpServer blow up
     * later}.<p>
     * 
     * The implementation is thread-safe and non-blocking.<p>
     * 
     * The implementation does not necessarily implement {@code hashCode()} and
     * {@code equals()}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Builder
    {
        /**
         * Set status code.
         * 
         * @param   statusCode value (any integer value)
         * @return  a new builder representing the new state
         * @see     StatusCode
         */
        Builder statusCode(int statusCode);
        
        /**
         * Set reason phrase.
         * 
         * @param   reasonPhrase value (any non-null string)
         * @throws  NullPointerException if {@code reasonPhrase} is {@code null}
         * @return  a new builder representing the new state
         * @see     ReasonPhrase
         */
        Builder reasonPhrase(String reasonPhrase);
        
        /**
         * Set a header. This overwrites all previously set values for the given
         * name.
         * 
         * @param   name of header
         * @param   value of header
         * @return  a new builder representing the new state
         * @throws  NullPointerException if any argument is {@code null}
         * @see     HttpConstants.HeaderKey
         */
        Builder header(String name, String value);
        
        /**
         * Remove all previously set values for the given header name.
         * 
         * @param name of the header
         * @return a new builder representing the new state
         * @throws  NullPointerException if {@code name} is {@code null}
         */
        Builder removeHeader(String name);
        
        /**
         * Remove all occurrences of a header that has the given value.
         * 
         * This method operates without regard to casing for both header name
         * and value.
         * 
         * @param name of the header
         * @param presentValue predicate
         * @return a new builder representing the new state
         * @throws  NullPointerException if any argument is {@code null}
         */
        Builder removeHeaderIf(String name, String presentValue);
        
        /**
         * Add a header to this response. If the header is already present then
         * it will be repeated in the response.
         * 
         * @param name of the header
         * @param value of the header
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if any argument or array element is {@code null}
         * 
         * @see HttpConstants.HeaderKey
         */
        Builder addHeader(String name, String value);
        
        /**
         * Add header(s) to this response.<p>
         * 
         * Iterating the {@code String[]} must alternate between header- names
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
         * 
         * @see HttpConstants.HeaderKey
         */
        Builder addHeaders(String name, String value, String... morePairs);
        
        /**
         * Add all headers from the given headers object.
         * 
         * @implSpec
         * The default implementation is
         * <pre>
         *     return this.{@link #addHeaders(Map)
         *       addHeaders}(headers.{@link ContentHeaders#delegate()
         *         delegate}().{@link HttpHeaders#map() map}());
         * </pre>
         * 
         * ...and does therefore not provide a guarantee regarding the ordering
         * of how the headers will appear in the response object.
         * 
         * @param   headers to add
         * @return  a new builder representing the new state
         * @throws  NullPointerException if {@code headers} is {@code null}
         * @see     HttpConstants.HeaderKey
         */
        default Builder addHeaders(ContentHeaders headers) {
            return addHeaders(headers.delegate().map());
        }
        
        /**
         * Add all headers from the given map.<p>
         * 
         * The order of the response headers will follow the iteration order of
         * the provided map.
         * 
         * @param   headers to add
         * @return  a new builder representing the new state
         * @throws  NullPointerException if {@code headers} is {@code null}
         * @see     HttpConstants.HeaderKey
         */
        Builder addHeaders(Map<String, List<String>> headers);
        
        /**
         * Set a message body.<p>
         * 
         * If never set, will default to an empty body.<p>
         * 
         * Each response transmission will cause the server to subscribe with a
         * new subscriber, consuming all of the remaining bytes in each
         * published bytebuffer.<p>
         * 
         * Most responses are probably only used once. But the application may
         * wish to cache and re-use responses. This is always safe if the
         * response is only sent to a dedicated client (two subscriptions
         * for the same client will never run in parallel). If the response is
         * re-used concurrently to different clients, then the body publisher
         * must be thread-safe and designed for concurrency; producing new
         * bytebuffers with the same data for each new subscriber.<p>
         * 
         * Multiple response objects derived/templated from the same builder(s)
         * will share the same underlying body publisher reference. So same rule
         * apply; the publisher must be thread-safe if the derivatives go out to
         * different clients.<p>
         * 
         * Response objects created by factory methods from the NoMagicHTTP
         * library API (and derivatives created from them) are fully thread-safe
         * and may be shared wildly. No worries! If none of these factories
         * suits you and there's a need to create a body publisher manually,
         * then consider using a publisher from {@link Publishers} or {@link
         * BetterBodyPublishers}. Not only are these defined to be thread-safe,
         * they are also non-blocking.<p>
         * 
         * If the body argument is a {@link BodyPublisher} and {@code
         * BodyPublisher.contentLength()} returns a negative value, then any
         * present {@code Content-Length} header will be removed (unknown
         * length). A positive value ({@code >= 0}) will set the header value
         * (possibly overwriting an old). So, it's unnecessary to set the header
         * manually as long as the body is a {@code BodyPublisher}.<p>
         * 
         * To remove an already set body, it's as easy as passing in as argument
         * to this method a {@link Publishers#empty()} or a {@code
         * BodyPublisher} with {@code contentLength() == 0}. Both cases will
         * also set the {@code Content-Length} header to 0.<p>
         * 
         * Any other publisher implementation must also be accompanied with an
         * explicit builder-call to either remove the {@code Content-Length}
         * header (for unknown length) or set it to the new length (or in
         * future; apply chunked encoding). Failure to comply may end up
         * re-using an old legacy {@code Content-Length} value with unspecified
         * application behavior as a result.<p>
         * 
         * A known length is always preferred over an unknown length as in the
         * latter case, the server will have to schedule a close of the
         * connection after the active exchange (in future: or apply chunked
         * encoding). There's no other way around that and still maintain proper
         * HTTP message framing.
         * 
         * @param   body publisher
         * @return  a new builder representing the new state
         * @throws  NullPointerException if {@code body} is {@code null}
         */
        Builder body(Flow.Publisher<ByteBuffer> body);
        
        /**
         * Builds the response.<p>
         * 
         * This method returns a new response object on each call.
         * 
         * @return a response
         * 
         * @throws IllegalResponseBodyException
         *             if a body is presumably not empty (see {@link
         *             Response#isBodyEmpty()}) and the status code is any one
         *             1XX (Informational), 204 (No Content) or 304 (Not
         *             Modified) 
         * 
         * @throws IllegalStateException
         *             if the write stream of the channel or the channel has
         *             been marked to shutdown/close and status-code is 1XX
         *             (Informational)
         * 
         * @throws IllegalStateException
         *             if response contains multiple {@code Content-Length} headers
         * 
         * @throws IllegalStateException
         *             if status code is 1XX (Informational) and header {@code
         *             Connection: close} is set (see
         *             <a href="https://tools.ietf.org/html/rfc7230#section-6.1">RFC 7231 §6.1</a>)
         */
        Response build();
    }
}