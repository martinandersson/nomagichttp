package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.util.Publishers;
import alpha.nomagichttp.util.SafeBodyPublishers;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * A response contains a {@link #statusLine() statusLine}, {@link #headers()
 * headers} and an optional {@link #body() body}.<p>
 * 
 * A {@code Response} can be built using {@link #newBuilder()} or other static
 * methods found in {@link Response.Builder} and {@link Responses}.<p>
 * 
 * The content of the request head (status-line and headers) will be written
 * to the client verbatim/unaltered; i.e. casing will be preserved, yes, even
 * space characters. The content is encoded into bytes using {@link
 * StandardCharsets#US_ASCII US_ASCII}<p>
 * 
 * The implementation is immutable and can safely be re-used sequentially over
 * time to the same client as well as shared concurrently to different
 * clients.<p>
 * 
 * The implementation does not necessarily implement {@code hashCode()} and
 * {@code equals()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see RequestHandler
 * @see ErrorHandler
 */
public interface Response
{
    /**
     * Returns a {@code Response} builder.<p>
     * 
     * @return a builder (doesn't have to be a new instance)
     */
    static Builder newBuilder() {
        return DefaultResponse.Builder.ROOT;
    }
    
    /**
     * Returns the status-line.<p>
     * 
     * The status-line consists of an HTTP-version, a status-code and a
     * reason-phrase. For example: "HTTP/1.1 200 OK".
     * 
     * @return the status-line
     */
    String statusLine();
    
    /**
     * Returns the headers (possibly empty).
     * 
     * @return the headers (possibly empty)
     */
    Iterable<String> headers();
    
    /**
     * Returns the message body (possibly empty).
     * 
     * @return the message body (possibly empty)
     */
    Flow.Publisher<ByteBuffer> body();
    
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
     */
    default CompletionStage<Response> asCompletedStage() {
        return CompletableFuture.completedStage(this);
    }
    
    /**
     * Returns {@code true} if the server must close the underlying client
     * channel after writing the response, otherwise {@code false}.<p>
     * 
     * The server is always free to close the channel even if this method
     * returns {@code false}, for example if the server run into channel-related
     * problems.<p>
     * 
     * For security; If closing the client channel fails, the server will try to
     * stop itself. If stopping itself fails, the server will exit the JVM.<p>
     * 
     * The channel's in- and output streams will shutdown first before channel
     * closure.
     * 
     * @implSpec
     * The default implementation returns @code false}.
     * 
     * @return {@code true} if the server must close the underlying client
     * channel, otherwise {@code false}
     */
    // TODO: Param that accepts mayInterruptRequestBodySubscriberOtherwiseWeWillWantForHim
    default boolean mustCloseAfterWrite() {
        return false;
    }
    
    /**
     * Builder of a {@link Response}.<p>
     * 
     * The builder type declares static methods that return builders already
     * populated with common status-lines such as {@link #ok()} and {@link
     * #accepted()}, what remains is to customize headers and the body. Static
     * methods that build a complete response can be found in {@link
     * Responses}.<p>
     * 
     * The builder can be used to modify per-response state. Each method returns
     * a new builder instance representing the new state which can be used
     * repeatedly as a template for new builds.<p>
     * 
     * HTTP version and status code must be set or {@link #build()} will fail.
     * The reason phrase if not set will default to "Unknown". Headers and the
     * body are optional.<p>
     * 
     * Header key and values are taken at face value, concatenated using a colon
     * followed by a space ": ". Headers may be duplicated (repeated in request
     * head).<p>
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
         * Returns a builder already populated with a status-line
         * "HTTP/1.1 200 OK".<p>
         *
         * What remains is to set headers and the message body.
         *
         * @return a builder
         */
        static Builder ok() {
            // TODO: Save references somewhere
            return newBuilder().httpVersion("HTTP/1.1")
                               .statusCode(200)
                               .reasonPhrase("OK");
        }
        
        /**
         * Returns a builder already populated with a status-line
         * "HTTP/1.1 202 Accepted".<p>
         *
         * What remains is to set headers and the message body.
         *
         * @return a builder
         */
        static Builder accepted() {
            // TODO: Save references somewhere
            return newBuilder().httpVersion("HTTP/1.1")
                               .statusCode(202)
                               .reasonPhrase("Accepted");
        }
        
        /**
         * Set HTTP version for this response.
         *
         * @throws NullPointerException if {@code httpVersion} is {@code null}
         *
         * @return a builder
         */
        Builder httpVersion(String httpVersion);
        
        /**
         * Set status code for this response.
         *
         * @return a builder
         */
        Builder statusCode(int statusCode);
        
        /**
         * Set reason phrase for this response. If never set, will default to
         * "Unknown".
         *
         * @throws NullPointerException if {@code reasonPhrase} is {@code null}
         *
         * @return a builder
         */
        Builder reasonPhrase(String reasonPhrase);
        
        /**
         * Add headers to this response.<p>
         *
         * Iterating the {@code String[]} must alternate between header- names
         * and values. To add several values to the same name then the same
         * name must be supplied with each new value.
         *
         * @param name of header
         * @param value of header
         * @param morePairs of headers
         *
         * @return a builder
         *
         * @throws NullPointerException if any argument is {@code null}
         * @throws IllegalArgumentException if {@code morePairs.length} is odd
         */
        Builder addHeaders(String name, String value, String... morePairs);
        
        /**
         * Set header name- and value pair for this response. This overwrites
         * all previously set values for name.
         * 
         * @param name of header
         * @param value of header
         * 
         * @return a builder
         * 
         * @throws NullPointerException if any argument is {@code null}
         */
        Builder header(String name, String value);
        
        /**
         * Set the "Content-Type" header for this response.<p>
         * 
         * Please note that changing the Content-Type ought to be followed by a
         * new response body.
         * 
         * @param type media type
         * 
         * @return a builder
         * 
         * @throws NullPointerException if {@code type} is {@code null}
         */
        Builder contentType(MediaType type);
        
        /**
         * Set the "Content-Length" header for this response.<p>
         * 
         * Please note that changing the Content-Length ought to be followed by
         * a new response body.
         * 
         * @param value content length
         *
         * @return this (for chaining/fluency)
         */
        Builder contentLenght(long value);
        
        /**
         * Set a message body. If never set, will default to an empty body.<p>
         * 
         * The published bytebuffers must not be modified after being published
         * to the subscriber.<p>
         * 
         * Depending on the application code, the body publisher may be exposed
         * to more than just one HTTP server thread at the same time. For
         * example, the body publisher instance may be shared by multiple
         * responses derived from the same builder targeting different clients
         * or the response instance itself containing the publisher may be sent
         * to different clients.<p>
         * 
         * Each new transmission will cause the HTTP server to subscribe with a
         * new subscriber, each of which is expected to receive the same data
         * using all new and subscription-unique bytebuffers.<p>
         * 
         * Please note that {@link Flow.Publisher} is not specified to be
         * thread-safe, and some implementations aren't (<a
         * href="https://bugs.openjdk.java.net/browse/JDK-8222968">JDK-8222968
         * </a>). Further, some JDK-provided types block, such as
         * {@link HttpRequest.BodyPublishers#ofFile(Path)} and {@link
         * HttpRequest.BodyPublishers#ofInputStream(Supplier)}. For these
         * reasons, consider using an alternative util in {@link Publishers} or
         * {@link SafeBodyPublishers}<p>
         * 
         * @return a builder
         * 
         * @throws NullPointerException if {@code body} is {@code null}
         */
        Builder body(Flow.Publisher<ByteBuffer> body);
        
        /**
         * Builds the response.<p>
         * 
         * If the state remains the same, then this method may return the same
         * response object on subsequent calls.<p>
         * 
         * @return a response
         * 
         * @throws IllegalStateException
         *             if HTTP version or reason phrase has not been set
         */
        Response build();
    }
}