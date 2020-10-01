package alpha.nomagichttp.message;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

import static java.net.http.HttpRequest.BodyPublisher;
import static java.net.http.HttpRequest.BodyPublishers;

/**
 * Factory methods for {@link Response}.
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
        return OK;
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
        return ResponseBuilder.ok()
                .contentType(contentType)
                .contentLenght(length)
                .body(body);
    }
    
    /**
     * Returns a "202 Accepted"-response with no body.
     * 
     * @return a "202 Accepted"-response with no body
     */
    public static Response accepted() {
        return ACCEPTED;
    }
    
    /**
     * Returns a "400 Bad Request"-response with no body.
     *
     * @return a "400 Bad Request"-response with no body
     */
    public static Response badRequest() {
        return new ResponseBuilder()
                .httpVersion("HTTP/1.1")
                .statusCode(400)
                .reasonPhrase("Bad Request")
                .mustCloseAfterWrite()
                .noBody();
    }
    
    /**
     * Returns a "404 Not Found"-response with no body.
     *
     * @return a "404 Not Found"-response with no body
     */
    public static Response notFound() {
        return new ResponseBuilder()
                .httpVersion("HTTP/1.1")
                .statusCode(404)
                .reasonPhrase("Not Found")
                .mustCloseAfterWrite()
                .noBody();
    }
    
    /**
     * Returns a "413 Entity Too Large"-response with no body.
     *
     * @return a "413 Entity Too Large"-response with no body
     */
    public static Response entityTooLarge() {
        return new ResponseBuilder()
                .httpVersion("HTTP/1.1")
                .statusCode(413)
                .reasonPhrase("Entity Too Large")
                .mustCloseAfterWrite()
                .noBody();
    }
    
    /**
     * Returns a "500 Internal Server Error"-response with no body.
     *
     * @return a "500 Internal Server Error"-response with no body
     */
    public static Response internalServerError() {
        return new ResponseBuilder()
                .httpVersion("HTTP/1.1")
                .statusCode(500)
                .reasonPhrase("Internal Server Error")
                .mustCloseAfterWrite()
                .noBody();
    }
    
    /**
     * Returns a "501 Not Implemented"-response with no body.
     *
     * @return a "501 Not Implemented"-response with no body
     */
    public static Response notImplemented() {
        return new ResponseBuilder()
                .httpVersion("HTTP/1.1")
                .statusCode(501)
                .reasonPhrase("Not Implemented")
                .mustCloseAfterWrite()
                .noBody();
    }
    
    private static final Response OK = ResponseBuilder.ok().noBody();
    private static final Response ACCEPTED = ResponseBuilder.accepted().noBody();
}