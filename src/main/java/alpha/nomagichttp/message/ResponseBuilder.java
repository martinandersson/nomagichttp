package alpha.nomagichttp.message;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ConcurrentModificationException;
import java.util.Queue;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Stream.of;

/**
 * Builds a {@link Response}.<p>
 * 
 * Building a response is composed of a number of steps. The HTTP-specification
 * mandates a status-line consisting of a {@code httpVersion}, {@code
 * statusCode} and {@code reasonPhrase}. Static methods exists that returns
 * builders already populated with common status-lines such as {@link #ok()} and
 * {@link #accepted()}. The status-line may be followed by optional headers and
 * message body.<p>
 * 
 * Status-line methods replace any value set previously. Header methods add the
 * header - duplicates allowed.<p>
 * 
 * Header key and values provided to header methods are concatenated using a
 * colon followed by a space ": ". Other then this, no validation or
 * interpretation at all is performed by this class.<p>
 * 
 * Setting the body using {@link #noBody()} or {@link #body(Flow.Publisher)} is
 * what actually "builds" the builder. These methods return a {@code Response}
 * view which is linked to the builder instance. The builder instance should be
 * thrown away after use.
 * 
 * The builder instance is not thread-safe.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Should be able to be re-used as template
public final class ResponseBuilder
{
    /**
     * Returns a builder already populated with a status-line
     * "HTTP/1.1 200 OK".<p>
     * 
     * What remains is to set headers and the message body.
     * 
     * @return a semi-populated builder
     */
    public static ResponseBuilder ok() {
        return new ResponseBuilder()
                .httpVersion("HTTP/1.1")
                .statusCode(200)
                .reasonPhrase("OK");
    }
    
    /**
     * Returns a builder already populated with a status-line
     * "HTTP/1.1 202 Accepted".<p>
     *
     * What remains is to set headers and the message body.
     *
     * @return a semi-populated builder
     */
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
    private boolean mustCloseAfterWrite;
    // TODO: This class was originally designed thread-safe. Not being
    //       thread-safe renders the non-atomic modCount variable and
    //       ConcurrentModificationException pretty useless. Remove.
    private int modCount;
    // TODO: The original thread-safe design also had lazy population of the
    //       response head in mind. Remove.
    private boolean committed;
    
    public ResponseBuilder() {
        // TODO: Take in version, code and phrase in constructor and remove corresponding builder methods.
        httpVersion = null;
        statusCode = 0;
        reasonPhrase = null;
        // TODO: Create lazily on first use
        headers = new ArrayDeque<>();
        body = null;
        modCount = 0;
        committed = false;
    }
    
    /**
     * Set HTTP version.
     * 
     * @throws NullPointerException if {@code httpVersion} is {@code null}
     * 
     * @return this (for chaining/fluency)
     */
    public ResponseBuilder httpVersion(String httpVersion) {
        requireNonNull(httpVersion);
        modifying(() -> this.httpVersion = httpVersion);
        return this;
    }
    
    /**
     * Set status-code.
     *
     * @return this (for chaining/fluency)
     */
    public ResponseBuilder statusCode(int statusCode) {
        modifying(() -> this.statusCode = statusCode);
        return this;
    }
    
    /**
     * Set reason-phrase.
     *
     * @throws NullPointerException if {@code reasonPhrase} is {@code null}
     *
     * @return this (for chaining/fluency)
     */
    public ResponseBuilder reasonPhrase(String reasonPhrase) {
        requireNonNull(reasonPhrase);
        modifying(() -> this.reasonPhrase = reasonPhrase);
        return this;
    }
    
    /**
     * Add a header key and value pair.
     *
     * @throws NullPointerException if any argument is {@code null}
     *
     * @return this (for chaining/fluency)
     */
    public ResponseBuilder header(String name, String value) {
        requireNonNull(name);
        requireNonNull(value);
        modifying(() -> headers.add(name + ": " + value));
        return this;
    }
    
    /**
     * Add a repeated header with what should be different values.
     *
     * @throws NullPointerException if any argument is effectively {@code null}
     *
     * @return this (for chaining/fluency)
     */
    public ResponseBuilder header(String name, Iterable<String> values) {
        values.forEach(v -> header(name, v));
        return this;
    }
    
    /**
     * Add a repeated header with what should be different values.
     *
     * @throws NullPointerException if any argument is {@code null}
     *
     * @return this (for chaining/fluency)
     */
    public ResponseBuilder header(String name, String firstValue, String... moreValues) {
        Stream.concat(of(firstValue), of(moreValues))
                .forEach(v -> header(name, v));
        
        return this;
    }
    
    // TODO: Rename to headerContentType() or move all named headers to sub-API
    //       like responseBuilder.header().contentType("val")
    
    /**
     * Add a "Content-Type" header.<p>
     * 
     * As with other header-methods, this method too allows repeated headers.
     * However, it is not recommended to repeat the "Content-Type" header.<p>
     * 
     * @param type media type
     * 
     * @return this (for chaining/fluency)
     * 
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public ResponseBuilder contentType(MediaType type) {
        return header("Content-Type", type.toString());
    }
    
    /**
     * Add a "Content-Length" header.<p>
     *
     * As with other header-methods, this method too allows repeated headers.
     * However, it is not recommended to repeat the "Content-Length" header.<p>
     *
     * @param value content length
     *
     * @return this (for chaining/fluency)
     */
    public ResponseBuilder contentLenght(long value) {
        return header("Content-Length", Long.toString(value));
    }
    
    /**
     * Instruct the server to close the client channel after writing the
     * response.
     * 
     * @return this (for chaining/fluency)
     * 
     * @see Response#mustCloseAfterWrite() 
     */
    // TODO: Move down after body-methods and the body methods no longer builds
    // the response. Need explicit build method for that.
    public ResponseBuilder mustCloseAfterWrite() {
        if (mustCloseAfterWrite) {
            return this;
        }
        
        modifying(() ->
            mustCloseAfterWrite = true);
        
        return this;
    }
    
    // TODO: Lots more of convenient-to-use header methods
    
    /**
     * Set an empty message body and return a {@code Response} view linked to
     * this builder.
     * 
     * @return a {@code Response} view
     */
    public Response noBody() {
        return contentLenght(0).body(Publishers.empty());
    }
    
    /**
     * Set the message body and return a {@code Response} view linked to this
     * builder.
     *
     * @return a {@code Response} view
     * 
     * @throws NullPointerException if {@code body} is {@code null}
     */
    public Response body(Flow.Publisher<ByteBuffer> body) {
        requireNonNull(body);
        
        if (this.body != null) {
            // Can hit this if the builder instance is re-used
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
    
    final class View implements Response {
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