package alpha.nomagichttp.message;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ConcurrentModificationException;
import java.util.Queue;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Stream.of;

// TODO: Document we work just like Java's HttpHeaders:
/*
 * An HTTP header name may appear more than once in the HTTP protocol. As
 * such, headers are represented as a name and a list of values. Each occurrence
 * of a header value is added verbatim, to the appropriate header name list,
 * without interpreting its value. In particular, {@code HttpHeaders} does not
 * perform any splitting or joining of comma separated header value strings. The
 * order of elements in a header value list is preserved when {@link
 * HttpRequest.Builder#header(String, String) building} a request. For
 * responses, the order of elements in a header value list is the order in which
 * they were received.
 * 
 * Other notes:
 * 
 * The returned response is just a linked view. The builder instance should be
 * discarded once built (response polled).
 */
// Not thread-safe and provides fail-fast exceptions only on a best-effort basis (no use of volatile variables)
// Fail-fast in the form of ConcurrentModificationException and IllegalStateException
public final class ResponseBuilder
{
    public static ResponseBuilder ok() {
        return new ResponseBuilder()
                .httpVersion("HTTP/1.1")
                .statusCode(200)
                .reasonPhrase("OK");
    }
    
    public static ResponseBuilder accepted() {
        return new ResponseBuilder()
                .httpVersion("HTTP/1.1")
                .statusCode(202)
                .reasonPhrase("Accepted");
    }
    
    private String httpVersion;
    private int statusCode;
    private String reasonPhrase;
    private final Queue<String> headers;
    private Flow.Publisher<ByteBuffer> body;
    private int modCount;
    private boolean committed;
    
    public ResponseBuilder() {
        httpVersion = null;
        statusCode = 0;
        reasonPhrase = null;
        headers = new ArrayDeque<>();
        body = null;
        modCount = 0;
        committed = false;
    }
    
    /**
     * TODO: Document
     * 
     * Replaces previous values.
     * 
     * Throws IllegalStateException if server has pulled the status-line or
     * headers already (i.e. response has commenced).
     */
    public ResponseBuilder httpVersion(String httpVersion) {
        requireNonNull(httpVersion);
        modifying(() -> this.httpVersion = httpVersion);
        return this;
    }
    
    // TODO: Document
    public ResponseBuilder statusCode(int statusCode) {
        modifying(() -> this.statusCode = statusCode);
        return this;
    }
    
    // TODO: Document
    public ResponseBuilder reasonPhrase(String reasonPhrase) {
        requireNonNull(reasonPhrase);
        modifying(() -> this.reasonPhrase = reasonPhrase);
        return this;
    }
    
    // TODO: Document
    public ResponseBuilder header(String name, String value) {
        requireNonNull(name);
        requireNonNull(value);
        modifying(() -> headers.add(name + ": " + value));
        return this;
    }
    
    // TODO: Document
    public ResponseBuilder header(String name, Iterable<String> values) {
        values.forEach(v -> modifying(() -> header(name, v)));
        return this;
    }
    
    // TODO: Document
    public ResponseBuilder header(String name, String firstValue, String... moreValues) {
        Stream.concat(of(firstValue), of(moreValues))
                .forEach(v -> modifying(() -> header(name, v)));
        
        return this;
    }
    
    // TODO: Document
    // Does not throw exception if quality is set to anything different than 1 (DefaultHandler do).
    // As with all other headers, this also appends. But it would be weird to
    // send many different content types to client.
    public ResponseBuilder contentType(MediaType type) {
        return header("Content-Type", type.toString());
    }
    
    // TODO: Document
    public ResponseBuilder contentLenght(long value) {
        return header("Content-Length", Long.toString(value));
    }
    
    // TODO: Lots more of convenient-to-use header methods
    
    public Response noBody() {
        return contentLenght(0).body(Publishers.empty());
    }
    
    // TODO: Document
    // Does not allow replace of old value. Call exactly once by calling
    // request-thread before handler return.
    public Response body(Flow.Publisher<ByteBuffer> body) {
        requireNonNull(body);
        
        if (this.body != null) {
            throw new IllegalStateException("Response body already set.");
        }
        
        this.body = body;
        return new View();
    }
    
    /**
     * Convenient wrapper around the {@code modCount} variable for all
     * head-modifying operations.
     *
     * @param action modifying action to run (in addition to updating modCount)
     */
    private void modifying(Runnable action) {
        if (committed) {
            throw new IllegalStateException("Response head committed.");
        }
        
        final int then = ++modCount;
        action.run();
        
        if (modCount != then) {
            throw new ConcurrentModificationException();
        }
        
        if (committed) {
            throw new IllegalStateException(
                    "Committed during modification. " +
                    "Changes might or might not have made it to the response.");
        }
    }
    
    class View implements Response {
        @Override
        public String statusLine() {
            committed = true;
            return httpVersion + " " + statusCode + " " + reasonPhrase;
        }
        
        @Override
        public Iterable<String> headers() {
            return headers;
        }
        
        @Override
        public Flow.Publisher<ByteBuffer> body() {
            return body;
        }
    }
}