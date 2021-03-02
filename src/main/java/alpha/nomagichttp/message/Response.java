package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.util.BetterBodyPublishers;
import alpha.nomagichttp.util.Publishers;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * A {@code Response} contains a {@link #statusLine() statusLine}, {@link
 * #headers() headers} and an optional {@link #body() body}. It can be built
 * using a {@link #builder()} or other static methods found in {@link
 * Response.Builder} and {@link Responses}.<p>
 * 
 * The content of the request head (status-line and headers) will be written
 * to the client verbatim/unaltered; i.e. casing will be preserved, yes, even
 * space characters. The content is encoded into bytes using {@link
 * StandardCharsets#US_ASCII US_ASCII}<p>
 * 
 * Header order (FIFO) is preserved (unless documented otherwise). Duplicated
 * header names will be grouped together and inserted at the occurrence of the
 * first value.<p>
 * 
 * The {@code Response} implementation is immutable and can safely be reused
 * sequentially over time to the same client as well as shared concurrently to
 * different clients.<p>
 * 
 * The {@code Response} implementation does not necessarily implement {@code
 * hashCode()} and {@code equals()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Response.Builder
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
    static Builder builder() {
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
     * For security; If closing the client channel fails, the server will try to
     * stop itself. If stopping itself fails, the server will exit the JVM.<p>
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
     * Builder of a {@link Response}.<p>
     * 
     * The builder type declares static methods that return builders already
     * populated with common status lines such as {@link #ok()} and {@link
     * #accepted()}, what remains is to customize headers and the body. Static
     * methods that build a complete response can be found in
     * {@link Responses}.<p>
     * 
     * The builder can be used as a template to modify per-response state. Each
     * method returns a new builder instance representing the new state. The API
     * should be used in a fluent style with references saved and reused only
     * for templating.<p>
     * 
     * HTTP version and status code must be set or {@link #build()} will fail.
     * The reason phrase if not set will default to "Unknown". Headers and the
     * body are optional.<p>
     * 
     * Header key and values are taken at face value (case-sensitive),
     * concatenated using a colon followed by a space ": ". Adding many values
     * to the same header name replicates the name across multiple rows in the
     * response. It does <strong>not</strong> join the values on the same row.
     * If this is desired, first join multiple values and then pass it to the
     * builder as one.<p>
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
         * @return a new builder representing the new state
         */
        static Builder ok() {
            return BuilderCache.OK;
        }
        
        /**
         * Returns a builder already populated with a status-line
         * "HTTP/1.1 202 Accepted".<p>
         * 
         * What remains is to set headers and the message body.
         * 
         * @return a new builder representing the new state
         */
        static Builder accepted() {
            return BuilderCache.ACCEPTED;
        }
        
        // TODO: Basically all other codes in the standard lol
        
        /**
         * Set HTTP version.
         * 
         * @param httpVersion value (any non-null string)
         * 
         * @throws NullPointerException if {@code httpVersion} is {@code null}
         * 
         * @return a new builder representing the new state
         */
        Builder httpVersion(String httpVersion);
        
        /**
         * Set status code.
         * 
         * @param statusCode value (any integer value)
         * 
         * @return a new builder representing the new state
         */
        Builder statusCode(int statusCode);
        
        /**
         * Set reason phrase. If never set, will default to "Unknown".
         * 
         * @param reasonPhrase value (any non-null string)
         * 
         * @throws NullPointerException if {@code reasonPhrase} is {@code null}
         * 
         * @return a new builder representing the new state
         */
        Builder reasonPhrase(String reasonPhrase);
        
        /**
         * Set a header. This overwrites all previously set values for the given
         * name.
         * 
         * @param name of header
         * @param value of header
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException if any argument is {@code null}
         */
        Builder header(String name, String value);
        
        /**
         * Set the "Content-Type" header.<p>
         * 
         * Please note that changing the Content-Type ought to be followed by a
         * new response body.
         * 
         * @param type media type
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException if {@code type} is {@code null}
         */
        Builder contentType(MediaType type);
        
        /**
         * Set the "Content-Length" header.<p>
         * 
         * Please note that changing the Content-Length ought to be followed by
         * a new response body.
         * 
         * @param value content length
         * 
         * @return a new builder representing the new state
         */
        Builder contentLenght(long value);
        
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
         */
        Builder addHeaders(String name, String value, String... morePairs);
        
        /**
         * Add all headers from the given {@code HttpHeaders}.<p>
         * 
         * The implementation may use {@code HttpHeaders.map()} which does not
         * provide any guarantee with regard to the ordering of its entries.
         * 
         * @param headers to add
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException if {@code headers} is {@code null}
         */
        Builder addHeaders(HttpHeaders headers);
        
        /**
         * Set a message body. If never set, will default to an empty body and
         * set "Content-Length: 0".<p>
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
         * reasons, consider using an alternative from {@link Publishers} or
         * {@link BetterBodyPublishers}<p>
         * 
         * @param body publisher
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException if {@code body} is {@code null}
         */
        Builder body(Flow.Publisher<ByteBuffer> body);
        
        /**
         * Set the {@code must-close-after-write} setting. If never set, will
         * default to false.
         * 
         * @param enabled true or false
         * 
         * @return a new builder representing the new state
         * 
         * @see Response#mustCloseAfterWrite()
         */
        Builder mustCloseAfterWrite(boolean enabled);
        
        /**
         * Builds the response.<p>
         * 
         * The returned response may be a cached object if previously built.<p>
         * 
         * @return a response
         * 
         * @throws IllegalStateException
         *             if HTTP version or status code has not been set
         */
        Response build();
    }
}

final class BuilderCache
{
    private static final Response.Builder HTTP_1_1 = Response.builder().httpVersion("HTTP/1.1");
    
    private BuilderCache() {
        // Empty
    }
    
    static final Response.Builder
            OK       = HTTP_1_1.statusCode(200).reasonPhrase("OK"),
            ACCEPTED = HTTP_1_1.statusCode(202).reasonPhrase("Accepted");
}