package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.util.BetterBodyPublishers;
import alpha.nomagichttp.util.Publishers;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.HttpConstants.ReasonPhrase;
import static alpha.nomagichttp.HttpConstants.StatusCode;
import static alpha.nomagichttp.message.Response.builder;

/**
 * A {@code Response} contains a status line, followed by optional headers and
 * body.<p>
 * 
 * Can be built using a builder:
 * 
 * <pre>{@code
 *   Response r = Response.builder(204, "No Content") // Or use HttpConstants
 *                        .header("My-Header", "value")
 *                        .build();
 * }</pre>
 * 
 * {@code Response} is immutable but may be converted back into a builder for
 * templating. This example uses a factory from {@link Responses} and is
 * equivalent to the previous example:
 * 
 * <pre>{@code
 *   Response r = Responses.noContent()
 *                         .toBuilder()
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
 * The {@code Response} object can safely be reused sequentially over time to
 * the same client. The response can also be shared concurrently to different
 * clients, assuming the {@linkplain Builder#body(Flow.Publisher) body
 * publisher} is thread-safe. If the publisher instance was retrieved using any
 * method provided by the NoMagicHTTP library, then it is thread-safe.<p>
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
public interface Response
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
        return DefaultResponse.Builder.ROOT.statusCode(statusCode);
    }
    
    /**
     * Returns a {@code Response} builder.<p>
     * 
     * @param statusCode response status code
     * @param reasonPhrase response reason phrase
     * @return a builder (doesn't have to be a new instance)
     * @see #statusCode()
     * @see #reasonPhrase()
     * @throws NullPointerException if {@code reasonPhrase} is {@code null}
     */
    static Builder builder(int statusCode, String reasonPhrase) {
        return builder(statusCode).reasonPhrase(reasonPhrase);
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
     * Returns the headers.
     * 
     * @return the headers (unmodifiable and possibly empty)
     */
    Iterable<String> headers();
    
    /**
     * Returns the message body (possibly empty).
     * 
     * @return the message body (possibly empty)
     */
    Flow.Publisher<ByteBuffer> body();
    
    /**
     * Returns {@code true} if the server must close the underlying client
     * channel after writing the response, otherwise {@code false}.<p>
     * 
     * The server is always free to close the channel even if this method
     * returns {@code false}, for example if the server run into channel-related
     * problems.<p>
     * 
     * The channel's in- and output streams will shutdown first before channel
     * closure.
     * 
     * @return {@code true} if the server must close the underlying client
     * channel, otherwise {@code false}
     */
    // TODO: Param that accepts mayInterruptRequestBodySubscriberOtherwiseWeWillWantForHim
    boolean mustCloseAfterWrite();
    
    /**
     * Returns this response object boxed in a completed stage.<p>
     * 
     * Useful for synchronous request handler implementations that are able to
     * build the response immediately without blocking.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *     return CompletableFuture.completedStage(this);
     * }</pre>
     * 
     * @return this response object boxed in a completed stage
     * 
     * @see HttpServer
     */
    default CompletionStage<Response> completedStage() {
        return CompletableFuture.completedStage(this);
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
     * The builder can be used as a template to modify per-response state. Each
     * method returns a new builder instance representing the new state. The API
     * should be used in a fluent style with references saved and reused only
     * for templating.<p>
     * 
     * Status code is the only required field. Please note that some message
     * variants may build just fine but {@linkplain HttpServer blow up
     * later}.<p>
     * 
     * Header key and values are taken at face value (case-sensitive),
     * concatenated using a colon followed by a space ": ". Adding many values
     * to the same header name replicates the name across multiple rows in the
     * response. It does <strong>not</strong> join the values on the same row.
     * If this is desired, first join multiple values and then pass it to the
     * builder as one.<p>
     * 
     * Header order is not significant (
     * <a href="https://tools.ietf.org/html/rfc7230#section-3.2.2">RFC 7230 §3.2.2</a>
     * ), but will be preserved (FIFO) except for duplicated names which will be
     * grouped together and inserted at the occurrence of the first value.<p>
     * 
     * The implementation is thread-safe.<p>
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
         * 
         * @return a new builder representing the new state
         */
        Builder removeHeader(String name);
        
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
         * Add all headers from the given HttpHeaders object.<p>
         * 
         * The implementation may use {@link HttpHeaders#map()} to access the
         * header values which does not provide any guarantee with regards to
         * the ordering of its entries.
         * 
         * @param   headers to add
         * @return  a new builder representing the new state
         * @throws  NullPointerException if {@code headers} is {@code null}
         * @see     HttpConstants.HeaderKey
         */
        Builder addHeaders(HttpHeaders headers);
        
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
         * Please note that multiple response objects derived/templated from the
         * same builder(s) will share the same underlying body publisher
         * reference. So same rule apply; the publisher must be thread-safe if
         * the derivatives go out to different clients.<p>
         * 
         * Response objects created by factory methods from the NoMagicHTTP
         * library API (and derivatives created from them) are fully thread-safe
         * and may be shared wildly. No worries! If none of these factories
         * suits you and there's a need to create a body publisher manually,
         * then consider using a publisher from {@link Publishers} or {@link
         * BetterBodyPublishers}. Not only are these defined to be thread-safe,
         * they are also non-blocking.<p>
         * 
         * @param   body publisher
         * @return  a new builder representing the new state
         * @throws  NullPointerException if {@code body} is {@code null}
         */
        Builder body(Flow.Publisher<ByteBuffer> body);
        
        /**
         * Set the {@code must-close-after-write} setting. If never set, will
         * default to false.
         * 
         * @param   enabled true or false
         * @return  a new builder representing the new state
         * @see     Response#mustCloseAfterWrite()
         */
        Builder mustCloseAfterWrite(boolean enabled);
        
        /**
         * Builds the response.<p>
         * 
         * The returned response may be a cached object if previously built.<p>
         * 
         * @return a response
         * 
         * @throws IllegalBodyException
         *             if body is present and status-code is 1XX (Informational)
         */
        Response build();
    }
}