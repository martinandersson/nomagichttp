package alpha.nomagichttp.message;

import alpha.nomagichttp.util.SafeBodyPublishers;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

import static java.net.http.HttpRequest.BodyPublisher;
import static java.net.http.HttpRequest.BodyPublishers;

/**
 * Utility methods for building complete {@link Response}s.<p>
 * 
 * For a more fine-grained control, use {@link Response#newBuilder()} or
 * semi-populated builders such as {@link Response.Builder#ok()} and {@link
 * Response.Builder#accepted()}.<p>
 * 
 * <strong>WARNING:</strong> Using {@link BodyPublishers} to create the response
 * body may not be thread-safe where thread-safety matters or may block the HTTP
 * server thread. Consider using {@link SafeBodyPublishers} instead. See
 * {@link Response.Builder#body(Flow.Publisher)}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Responses
{
    private Responses() {
        // Empty
    }
    
    /**
     * Returns a "200 OK"-response with no body.
     * 
     * @return a "200 OK"-response with no body
     */
    public static Response ok() {
        return Cache.OK;
    }
    
    /**
     * Returns a "200 OK"-response with a text body.<p>
     * 
     * The Content-Type header will be set to "text/plain; charset=utf-8".
     * 
     * @param textPlain message body
     * 
     * @return a "200 OK"-response with a text body
     */
    public static Response ok(String textPlain) {
        return ok("text/plain; charset=utf-8", BodyPublishers.ofString(textPlain));
    }
    
    /**
     * Returns a "200 OK"-response with an arbitrary body.<p>
     * 
     * @param contentType header value
     * @param body publisher with content length
     * 
     * @return a "200 OK"-response with an arbitrary body
     */
    public static Response ok(String contentType, BodyPublisher body) {
        return ok(MediaType.parse(contentType), body, body.contentLength());
    }
    
    /**
     * Returns a "200 OK"-response with an arbitrary body.<p>
     * 
     * @param contentType header value
     * @param body publisher with content length
     * 
     * @return a "200 OK"-response with an arbitrary body
     */
    public static Response ok(MediaType contentType, BodyPublisher body) {
        return ok(contentType, body, body.contentLength());
    }
    
    /**
     * Returns a "200 OK"-response with an arbitrary body.<p>
     * 
     * @param contentType header value
     * @param body publisher
     * @param length of body (will be set as "Content-Length" header value)
     *
     * @return a "200 OK"-response with an arbitrary body
     */
    public static Response ok(String contentType, Flow.Publisher<ByteBuffer> body, long length) {
        return ok(MediaType.parse(contentType), body, length);
    }
    
    /**
     * Returns a "200 OK"-response with an arbitrary body.<p>
     * 
     * @param contentType header value
     * @param body publisher
     * @param length of body (will be set as "Content-Length" header value)
     *
     * @return a "200 OK"-response with an arbitrary body
     */
    public static Response ok(MediaType contentType, Flow.Publisher<ByteBuffer> body, long length) {
        return Response.Builder.ok()
                .contentType(contentType)
                .contentLenght(length)
                .body(body)
                .build();
    }
    
    /**
     * Returns a "202 Accepted"-response with no body.
     * 
     * @return a "202 Accepted"-response with no body
     */
    public static Response accepted() {
        return Cache.ACCEPTED;
    }
    
    /**
     * Returns a "400 Bad Request"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return a "400 Bad Request"-response with no body
     */
    public static Response badRequest() {
        return Cache.BAD_REQUEST;
    }
    
    /**
     * Returns a "404 Not Found"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return a "404 Not Found"-response with no body
     */
    public static Response notFound() {
        return Cache.NOT_FOUND;
    }
    
    /**
     * Returns a "413 Entity Too Large"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return a "413 Entity Too Large"-response with no body
     */
    public static Response entityTooLarge() {
        return Cache.ENTITY_TOO_LARGE;
    }
    
    /**
     * Returns a "500 Internal Server Error"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return a "500 Internal Server Error"-response with no body
     */
    public static Response internalServerError() {
        return Cache.INTERNAL_SERVER_ERROR;
    }
    
    /**
     * Returns a "501 Not Implemented"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return a "501 Not Implemented"-response with no body
     */
    public static Response notImplemented() {
        return Cache.NOT_IMPLEMENTED;
    }
    
    /**
     * Pre-built response objects with no payloads (message body).
     */
    private static final class Cache {
        static final Response
                OK                    = Response.Builder.ok().build(),
                ACCEPTED              = Response.Builder.accepted().build(),
                BAD_REQUEST           = respondThenClose(400, "Bad Request"),
                NOT_FOUND             = respondThenClose(404, "Not Found"),
                ENTITY_TOO_LARGE      = respondThenClose(413, "Entity Too Large"),
                INTERNAL_SERVER_ERROR = respondThenClose(500, "Internal Server Error"),
                NOT_IMPLEMENTED       = respondThenClose(501, "Not Implemented");
        
        private static Response respondThenClose(int code, String phrase) {
            return Response.newBuilder()
                    .httpVersion("HTTP/1.1")
                    .statusCode(code)
                    .reasonPhrase(phrase)
                    .mustCloseAfterWrite(true)
                    .build();
        }
    }
}