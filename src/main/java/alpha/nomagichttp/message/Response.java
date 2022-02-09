package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.util.BetterBodyPublishers;
import alpha.nomagichttp.util.Headers;
import alpha.nomagichttp.util.Publishers;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.HttpConstants.ReasonPhrase;
import static alpha.nomagichttp.HttpConstants.StatusCode;
import static java.net.http.HttpRequest.BodyPublisher;

/**
 * Contains a status line, followed by optional headers, body and trailers.<p>
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
 * retrieved and used to create new responses derived from the first. This
 * effectively makes the {@link Responses} class a repository of commonly used
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
 * {@code Responses} in combination with body factories in {@link
 * BetterBodyPublishers} ought to cover for the most common use cases.
 * 
 * <pre>{@code
 *   BodyPublisher body = BetterBodyPublishers.ofByteArray(...);
 *   Response r = Responses.ok(body); // 200 OK
 * }</pre>
 * 
 * The content of the response head (status line and headers) will be written
 * to the client verbatim/unaltered; i.e. casing and white space will be
 * preserved.<p>
 * 
 * The {@code Response} object can safely be reused sequentially over time to
 * the same client. The response can also be shared concurrently to different
 * clients, assuming the {@linkplain Builder#body(Flow.Publisher) body
 * publisher} is thread-safe. If the publisher instance was retrieved using any
 * method provided by the NoMagicHTTP library (e.g. {@link
 * BetterBodyPublishers}), then it is fully thread-safe and non-blocking. All
 * responses with a body created by {@link Responses} that does not explicitly
 * accept a body publisher as an argument uses a thread-safe body publisher
 * under the hood.<p>
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
    Flow.Publisher<ByteBuffer> body();
    
    /**
     * Returns trailers.
     * 
     * @return trailers
     * @see Request#trailers() 
     */
    Optional<CompletionStage<HttpHeaders>> trailers();
    
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
     * {@link HttpRequest.BodyPublisher} with {@code contentLength()} set to 0,
     * or the response has a {@code Content-Length} header set to 0.
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
     * The builder is immutable. All builder-returning methods return a new
     * builder instance representing the new state. The builder can be used as a
     * template to derive new responses. {@link Response#toBuilder()} returns
     * the builder that built the response which effectively makes all responses
     * into templates as well.<p>
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
         * Set a header.<p>
         * 
         * This overwrites all previously set values for the given name
         * (case-sensitive).
         * 
         * @param   name of header
         * @param   value of header
         * @return  a new builder representing the new state
         * @throws  NullPointerException if any argument is {@code null}
         * @see     HttpConstants.HeaderName
         */
        Builder header(String name, String value);
        
        /**
         * Remove all occurrences of a header.<p>
         * 
         * This method operates without regard to casing.
         * 
         * @param name of the header
         * @return a new builder representing the new state
         * @throws NullPointerException if {@code name} is {@code null}
         */
        Builder removeHeader(String name);
        
        /**
         * Remove all occurrences of given a header value.<p>
         * 
         * This method operates without regard to casing for both header name
         * and value.<p>
         * 
         * If there are no mapped values left after the operation, the header
         * will also be removed.
         * 
         * @param name of the header
         * @param value predicate
         * @return a new builder representing the new state
         * @throws NullPointerException if any argument is {@code null}
         */
        Builder removeHeaderValue(String name, String value);
        
        /**
         * Add a header to this response.<p>
         * 
         * If the header is already present then it will be repeated in the
         * response.
         * 
         * @param name of the header
         * @param value of the header
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if any argument or array element is {@code null}
         * 
         * @see HttpConstants.HeaderName
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
         * @see HttpConstants.HeaderName
         */
        Builder addHeaders(String name, String value, String... morePairs);
        
        /**
         * Add all headers from the given headers object.
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
         * @param   headers to add
         * @return  a new builder representing the new state
         * @throws  NullPointerException if {@code headers} is {@code null}
         * @see     HttpConstants.HeaderName
         */
        default Builder addHeaders(BetterHeaders headers) { // TODO: Remove!
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
         * @see     HttpConstants.HeaderName
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
         * Add response trailers.<p>
         * 
         * The application should also populate the HTTP header "Trailer" with
         * the names of the trailers that will be present.<p>
         * 
         * If the HTTP exchange is using a version less than 1.1, the given
         * trailers will be silently discarded.<p>
         * 
         * Trailers will be written out on the wire in almost the same way
         * headers are. The only exception is that the order is not defined.<p>
         * 
         * Completing the stage exceptionally with a {@link
         * CancellationException} has the same effect as completing the stage
         * with an empty headers object, that is to say, no trailers will be
         * written.
         * 
         * @param trailers to add
         * @return a new builder representing the new state
         * @throws  NullPointerException if {@code trailers} is {@code null}
         * @see Request#trailers()
         * @see Headers
         * @see Config#rejectClientsUsingHTTP1_0() 
         */
        Builder addTrailers(CompletionStage<HttpHeaders> trailers);
        
        /**
         * Remove previously set trailers.
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
         * @throws IllegalResponseBodyException
         *             if a body is presumably not empty (see {@link
         *             Response#isBodyEmpty()}) and the status code is one of
         *             1XX (Informational), 204 (No Content) or 304 (Not
         *             Modified)
         * 
         * @throws IllegalStateException
         *             if headers are unaccepted,
         *             e.g. multiple {@code Content-Length} headers; or
         *             if status code is 1XX (Informational) and header {@code
         *             Connection: close} is set (see
         *             <a href="https://tools.ietf.org/html/rfc7230#section-6.1">RFC 7231 §6.1</a>)
         */
        Response build();
    }
}