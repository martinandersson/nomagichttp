package alpha.nomagichttp.message;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

import static java.net.http.HttpRequest.BodyPublisher;
import static java.net.http.HttpRequest.BodyPublishers;

// TODO: Document
public final class Responses
{
    private Responses() {
        // Empty
    }
    
    public static Response ok() {
        return OK;
    }
    
    public static Response ok(String textPlain) {
        return ok("text/plain; charset=utf-8", BodyPublishers.ofString(textPlain));
    }
    
    public static Response ok(String contentType, BodyPublisher body) {
        return ok(MediaType.parse(contentType), body, body.contentLength());
    }
    
    public static Response ok(MediaType contentType, BodyPublisher body) {
        return ok(contentType, body, body.contentLength());
    }
    
    public static Response ok(String contentType, Flow.Publisher<ByteBuffer> body, long length) {
        return ok(MediaType.parse(contentType), body, length);
    }
    
    public static Response ok(MediaType contentType, Flow.Publisher<ByteBuffer> body, long length) {
        return ResponseBuilder.ok()
                .contentType(contentType)
                .contentLenght(length)
                .body(body);
    }
    
    public static Response accepted() {
        return ACCEPTED;
    }
    
    private static final Response OK = ResponseBuilder.ok().noBody();
    private static final Response ACCEPTED = ResponseBuilder.accepted().noBody();
}