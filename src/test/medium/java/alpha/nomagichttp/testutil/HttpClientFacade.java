package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.DefaultContentHeaders;
import alpha.nomagichttp.util.Streams;
import io.netty.buffer.ByteBuf;
import kotlin.Pair;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.ByteBufMono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClient.ResponseReceiver;
import reactor.netty.http.client.HttpClientResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.testutil.Headers.linkedHashMap;
import static alpha.nomagichttp.util.Streams.toList;
import static java.net.http.HttpClient.Version;
import static java.net.http.HttpRequest.BodyPublisher;
import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpResponse.BodyHandlers;
import static java.nio.ByteBuffer.allocate;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.time.Duration.ofSeconds;
import static java.util.Arrays.stream;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;
import static org.apache.hc.core5.http.ContentType.APPLICATION_OCTET_STREAM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An HTTP client API that delegates to another {@link Implementation
 * Implementation}.<p>
 * 
 * The NoMagicHTTP's own {@link TestClient} is <i>not</i> supported as a
 * delegate. The {@code TestClient} should be used explicitly in its own
 * separated test as it offers a comprehensive low-level API (with background
 * assertions such as expect-no-trailing-bytes from the server) at the same time
 * it also makes the HTTP exchange more readable (most usages simply write and
 * read strings). Where suitable, a subsequent "compatibility" test using the
 * client facade can then be declared whose purpose it is to ensure, well,
 * compatibility. E.g.
 * <pre>
 *   {@literal @}Test
 *   void helloWorld() {
 *       // Very clear for human, and also very detailed, explicit (we like)
 *       TestClient client = ...
 *       String response = client.writeReadTextUntilNewlines("GET / HTTP/1.1 ...");
 *       assertThat(response).isEqualTo("HTTP/1.1 200 OK ...");
 *   }
 *   
 *   {@literal @}ParameterizedTest
 *   {@literal @}EnumSource
 *   void helloWorld_compatibility(HttpClientFacade.Implementation impl) {
 *       // Explosion of types and methods. Only God knows what happens. But okay, good having.
 *       int serverPort = ...
 *       HttpClientFacade client = impl.create(serverPort);
 *       ResponseFacade{@literal <}String{@literal >} response = client.getText("/", HTTP_1_1);
 *       assertThat(response.statusCode())...
 *   }
 * </pre>
 * 
 * The underlying clients used does not expose an API for user-control of the
 * connection, and so, the facade implementation can do no better. In fact, the
 * life-cycle and performance characteristics of the facade and its underlying
 * client objects are pretty much unknown. Most of them have - unfortunately
 * quite expectedly - zero documentation regarding the client's life-cycle and
 * how it should be cached and used. Never mind concerns such as thread-safety
 * and identity lol. Hence, this class will mostly not cache the client object,
 * using one new client instance for each request executed.<p>
 * 
 * A specified HTTP version may be rejected with an {@code
 * IllegalArgumentException}, but only on a best-effort basis. Which versions
 * specifically a client implementation supports is not always clear lol. The
 * argument will be passed forward to the client who may then blow up with
 * another exception.<p>
 * 
 * The underlying client connection will likely live in a connection pool (aka.
 * be persistent) until some weird stale timeout. And, attempts to change the
 * persistent state on the client's side may fail. For example, the JDK client
 * will throw an {@code IllegalArgumentException} if the "Connection: close"
 * header is set.<p>
 * 
 * Even though the test may have executed only one exchange; when it proceeds to
 * call {@link HttpServer#stop()}, a new logical exchange may have already
 * commenced and so the connection will not close until timeout — if specified
 * (and if not specified, the stop method may never return until, and if, the
 * client's connection pool times out!). So, unless a persistent connection is a
 * criteria for the test case itself, the request handler ought to set a
 * "Connection: close" response header. Regardless, it's always a good idea to
 * increase test isolation by cleaning up resources in-between lol.<p>
 * 
 * This class is not thread-safe and does not implement {@code hashCode} or
 * {@code equals}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: After modules, separate into its own package
// TODO: Add throws Exception, and one has to go to each impl's JavaDoc to see which ones
// TODO: Add JavaDoc links for each client
// TODO: See the JavaDoc above on how a new exchange may have begun, but without
//       actual data transmission, which does no good except for staling the
//       server stop. Thus, we need a "soft interrupt" mechanism! Then we may be
//       be able to remove the recommendation to set "Connection: close", and
//       consequently the implemented behavior in test classes (see
//       "_compatibility" methods in ExampleTest and MessageTest).
public abstract class HttpClientFacade
{
    /**
     * Supported delegate implementations.
     */
    public enum Implementation {
        /**
         * Supports version HTTP/1.1 and HTTP/2.<p>
         * 
         * Known perks:
         * <ol>
         *   <li>Has no API for retrieval of the reason-phrase.</li>
         *   <li>Does not support setting a "Connection" header.</li>
         *   <li>Does not support HTTP method CONNECT.</li>
         * </ol>
         */
        JDK (JDK::new),
        
        /**
         * What HTTP version this client supports is slightly unknown. JavaDoc
         * for OkHttp 4 (the client version currently used) is - perhaps not
         * surprisingly,
         * <a href="https://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/protocols/">empty</a>.
         * <a href="https://square.github.io/okhttp/3.x/okhttp/okhttp3/OkHttpClient.Builder.html#protocols-java.util.List-">OkHttp 3</a>
         * indicates HTTP/1.0 (and consequently 0.9?) is not supported. I guess
         * we'll find out.
         */
        OKHTTP (OkHttp::new),
        
        /**
         * Apache HttpClient.
         * 
         * @see <a href="https://hc.apache.org/httpcomponents-client-5.1.x/index.html">website</a>
         */
        APACHE (Apache::new),
        
        /**
         * Jetty HttpClient.
         * 
         * @see <a href="https://www.eclipse.org/jetty/documentation/jetty-11/programming-guide/index.html#pg-client-http">website</a>
         */
        JETTY (Jetty::new),
        
        /**
         * Reactor-Netty HttpClient.
         * 
         * @see <a href="https://projectreactor.io/docs/netty/release/reference/index.html#http-client">website</a>
         */
        REACTOR (Reactor::new);
        
        // TODO: Helidon has a client built on top of Netty. Add?
        
        /**
         * Construct all implementations.
         * 
         * @param port of server
         * @return a stream of client facades
         */
        public static Stream<HttpClientFacade> createAll(int port) {
            return createAllExceptFor(port);
        }
        
        /**
         * Construct almost all implementations, excluding some.
         * 
         * @param port of server
         * @param impl implementations to exclude
         * @return a stream of client facades
         */
        public static Stream<HttpClientFacade> createAllExceptFor(int port, Implementation... impl) {
            var stream = Arrays.stream(values());
            if (impl.length > 0) {
                var set = EnumSet.of(impl[0], impl);
                stream = stream.filter(not(set::contains));
            }
            return stream.map(i -> i.create(port));
        }
        
        private final Function<Integer, HttpClientFacade> factory;
        
        Implementation(Function<Integer, HttpClientFacade> f) {
            factory = f;
        }
        
        /**
         * Create the client facade.
         * 
         * @param port of server
         * @return a client facade
         */
        public final HttpClientFacade create(int port) {
            return factory.apply(port);
        }
    }
    
    private final int port;
    // "permits null elements", whatever that means
    private Map<String, List<String>> headers;
    
    /**
     * Initializes this object.
     * 
     * @param port of server
     */
    protected HttpClientFacade(int port) {
        this.port = port;
        this.headers = Map.of();
    }
    
    @Override
    public final String toString() {
        return getClass().getSimpleName();
    }
    
    /**
     * Add a header that will be added to each request this client sends.
     * 
     * @param name of header
     * @param value of header
     * @return this for chaining/fluency
     */
    public final HttpClientFacade addClientHeader(String name, String value) {
        requireNonNull(name);
        requireNonNull(value);
        if (headers.isEmpty()) { // i.e. == Map.of()
            headers = new LinkedHashMap<>();
        }
        headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
        return this;
    }
    
    /**
     * Create a "http://localhost:{port}" URI with the given path appended.
     * 
     * @param path of server resource (should start with forward slash)
     * @return a URI
     */
    protected final URI withBase(String path) {
        return URI.create("http://localhost:" + port + path);
    }
    
    /**
     * Copy all headers contained in this class into the given [mutable] sink.
     * 
     * @param sink target
     */
    protected void copyClientHeaders(BiConsumer<String, String> sink) {
        headers.forEach((name, values) ->
                values.forEach(v -> sink.accept(name, v)));
    }
    
    /**
     * Iterate all headers contained in this client.
     * 
     * @return an iterator
     */
    protected Iterable<Map.Entry<String, List<String>>> clientHeaders() {
        return headers.entrySet();
    }
    
    /**
     * Executes a GET request and expects no response body.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @return the response
     * 
     * @throws AssertionError
     *             if the response has a body
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new) 
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an underlying asynchronous operation times out
     *             (timeout duration not specified)
     */
    // Is not final only because Reactor-Netty needs a custom hacked solution
    public ResponseFacade<Void> getEmpty(String path, HttpConstants.Version version)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        return getBytes(path, version).assertEmpty();
    }
    
    /**
     * Executes a GET request and returns the response body non-decoded.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new) 
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an underlying asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version version)
            throws IOException, InterruptedException, ExecutionException, TimeoutException;
    
    /**
     * Executes a GET request and returns the response body as text.<p>
     * 
     * Which charset to use for decoding is for the client implementation to
     * decide. In practice, this will likely be extracted from the Content-Type
     * header.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new) 
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an underlying asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract ResponseFacade<String> getText(String path, HttpConstants.Version version)
            throws IOException, InterruptedException, ExecutionException, TimeoutException;
    
    /**
     * Executes a POST request and return the response body as text.<p>
     * 
     * Which charset to use for decoding is for the client implementation to
     * decide.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @param body of request
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new)
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an underlying asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract ResponseFacade<String> postAndReceiveText(
            String path, HttpConstants.Version version, String body)
            throws IOException, InterruptedException, ExecutionException, TimeoutException;
    
    /**
     * Executes a POST request expecting no response body.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @param body of request
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new)
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an underlying asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract ResponseFacade<Void> postBytesAndReceiveEmpty(
            String path, HttpConstants.Version version, byte[] body)
            throws IOException, InterruptedException, ExecutionException, TimeoutException;
    
    /**
     * Executes an HTTP/1.1 chunked-encoded POST request and returns the
     * response body as text.<p>
     * 
     * No client implementation supports or documents how to produce data chunks
     * of a particular size. If possible, the byte arrays given to this method
     * will be forwarded as-is to the client implementation which hopefully will
     * transport each as a new chunk.
     * 
     * @param path of server resources
     * @param firstChunk of request body
     * @param more chunks of the request body
     * @return the response
     * 
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     * @throws TimeoutException
     *             if an underlying asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract ResponseFacade<String> postChunksAndReceiveText(
            String path, byte[] firstChunk, byte[]... more)
            throws IOException, InterruptedException, ExecutionException, TimeoutException;
    
    private static final class JDK extends HttpClientFacade {
        private final java.net.http.HttpClient cli;
        
        JDK(int port) {
            super(port);
            cli = java.net.http.HttpClient.newHttpClient();
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws IOException, InterruptedException
        {
            return execute(GET(path, ver), BodyHandlers.ofByteArray());
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws IOException, InterruptedException
        {
            return execute(GET(path, ver), BodyHandlers.ofString());
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version ver, String body)
                throws IOException, InterruptedException
        {
            var req = POST(path, ver, BodyPublishers.ofString(body));
            return execute(req, BodyHandlers.ofString());
        }
        
        @Override
        public ResponseFacade<Void> postBytesAndReceiveEmpty(
                String path, HttpConstants.Version ver, byte[] body)
                throws IOException, InterruptedException
        {
            var req = POST(path, ver, BodyPublishers.ofByteArray(body));
            return execute(req, BodyHandlers.ofByteArray()).assertEmpty();
        }
        
        @Override
        public ResponseFacade<String> postChunksAndReceiveText(
                String path, byte[] firstChunk, byte[]... more)
                throws IOException, InterruptedException
        {
            var bufs = new ByteBufferPublisher(firstChunk, more);
            var req = POST(path, HTTP_1_1,
                    BodyPublishers.fromPublisher(bufs));
            return execute(req, BodyHandlers.ofString());
        }
        
        private HttpRequest GET(String path, HttpConstants.Version ver) {
            return newRequest("GET", path, ver, BodyPublishers.noBody());
        }
        
        private HttpRequest POST(String path, HttpConstants.Version ver, BodyPublisher body) {
            return newRequest("POST", path, ver, body);
        }
        
        private HttpRequest newRequest(
                String method, String path, HttpConstants.Version ver, BodyPublisher reqBody)
        {
            var b = HttpRequest.newBuilder()
                    .method(method, reqBody)
                    .uri(withBase(path))
                    .version(toJDKVersion(ver));
            copyClientHeaders(b::header);
            return b.build();
        }
        
        private <B> ResponseFacade<B> execute(
                HttpRequest req,
                HttpResponse.BodyHandler<B> rspBodyConverter)
                throws IOException, InterruptedException
        {
            var rsp = cli.send(req, rspBodyConverter);
            return ResponseFacade.fromJDK(rsp);
        }
        
        private static Version toJDKVersion(HttpConstants.Version ver) {
            return switch (ver) {
                case HTTP_0_9, HTTP_1_0, HTTP_3 ->
                    throw new IllegalArgumentException("Not supported.");
                case HTTP_1_1 -> Version.HTTP_1_1;
                case HTTP_2   -> Version.HTTP_2;
            };
        }
    }
    
    private static final class OkHttp extends HttpClientFacade {
        OkHttp(int port) {
            super(port);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws IOException {
            return execute(GET(path), ver, ResponseBody::bytes);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws IOException {
            return execute(GET(path), ver, ResponseBody::string);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version ver, String body)
                throws IOException {
            var req = POST(path, RequestBody.create(body, null));
            return execute(req, ver, ResponseBody::string);
        }
        
        @Override
        public ResponseFacade<Void> postBytesAndReceiveEmpty(
                String path, HttpConstants.Version ver, byte[] body)
                throws IOException {
            var req = POST(path, RequestBody.create(body, null));
            return execute(req, ver, ResponseBody::bytes).assertEmpty();
        }
        
        @Override
        public ResponseFacade<String> postChunksAndReceiveText(
                String path, byte[] firstChunk, byte[]... more) throws IOException {
            var req = POST(path, new RequestBody() {
                public MediaType contentType() {
                    return null;
                }
                public void writeTo(BufferedSink s) throws IOException {
                    for (var byteArr : toList(firstChunk, more)) {
                        s.write(byteArr);
                    }
                }
            });
            return execute(req, HTTP_1_1, ResponseBody::string);
        }
        
        private Request GET(String path) throws MalformedURLException {
            return newRequest("GET", path, null);
        }
        
        private Request POST(String path, RequestBody reqBody) throws MalformedURLException {
            return newRequest("POST", path, reqBody);
        }
        
        private Request newRequest(
                String method, String path, RequestBody reqBody)
                throws MalformedURLException
        {
            var req = new Request.Builder()
                    .method(method, reqBody)
                    .url(withBase(path).toURL());
            copyClientHeaders(req::header);
            return req.build();
        }
        
        private <B> ResponseFacade<B> execute(
                Request req,
                HttpConstants.Version ver,
                IOFunction<? super ResponseBody, ? extends B> rspBodyConverter)
                throws IOException
        {
            var cli = new OkHttpClient.Builder()
                    .protocols(List.of(toSquareVersion(ver)))
                    .build();
            // No close callback from our Response type, so must consume eagerly
            var rsp = cli.newCall(req).execute();
            B bdy;
            try (rsp) {
                bdy = rspBodyConverter.apply(rsp.body());
            }
            return ResponseFacade.fromOkHttp(rsp, bdy);
        }
        
        private static Protocol toSquareVersion(HttpConstants.Version ver) {
            return switch (ver) {
                case HTTP_0_9, HTTP_3 -> throw new IllegalArgumentException("Not supported.");
                case HTTP_1_0 -> Protocol.HTTP_1_0;
                case HTTP_1_1 -> Protocol.HTTP_1_1;
                case HTTP_2 -> Protocol.HTTP_2;
            };
        }
    }
    
    private static final class Apache extends HttpClientFacade {
        Apache(int port) {
            super(port);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws IOException {
            var r = req(HttpGet::new, path, ver);
            return execute(r, BYTE_ARR);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws IOException {
            var r = req(HttpGet::new, path, ver);
            return execute(r, STRING);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
            String path, HttpConstants.Version ver, String body)
                throws IOException {
            var r = req(HttpPost::new, path, ver);
            r.setEntity(new StringEntity(body));
            return execute(r, STRING);
        }
        
        @Override
        public ResponseFacade<Void> postBytesAndReceiveEmpty(
            String path, HttpConstants.Version ver, byte[] body)
                throws IOException {
            var r = req(HttpPost::new, path, ver);
            r.setEntity(new ByteArrayEntity(body, APPLICATION_OCTET_STREAM ));
            return execute(r, BYTE_ARR).assertEmpty();
        }
        
        @Override
        public ResponseFacade<String> postChunksAndReceiveText(
                String path, byte[] firstChunk, byte[]... more)
                throws IOException
        {
            // Sourced from
            // https://github.com/apache/httpcomponents-client/blob/5.1.x/httpclient5/src/test/java/org/apache/hc/client5/http/examples/ClientChunkEncodedPost.java
            var stream = new InputStreamEntity(
                    newInputStream(firstChunk, more), -1, null);
            var req = req(HttpPost::new, path, HTTP_1_1);
            req.setEntity(stream);
            return execute(req, STRING);
        }
        
        private <T extends HttpUriRequestBase> T req(
                Function<URI, T> method, String path, HttpConstants.Version ver)
        {
            var req = method.apply(withBase(path));
            req.setVersion(toApacheVersion(ver));
            copyClientHeaders(req::addHeader);
            return req;
        }
        
        private <B> ResponseFacade<B> execute(
                ClassicHttpRequest req, IOFunction<HttpEntity, B> rspBodyConverter)
                throws IOException {
            B body = null;
            try (var cli = HttpClients.createDefault()) {
                var rsp = cli.execute(req);
                try (rsp) {
                    var entity = rsp.getEntity();
                    if (entity != null) {
                        body = rspBodyConverter.apply(entity);
                    }
                }
                return ResponseFacade.fromApache(rsp, body);
            }
        }
        
        private static ProtocolVersion toApacheVersion(HttpConstants.Version ver) {
            return org.apache.hc.core5.http.HttpVersion
                    .get(ver.major(), ver.minor().orElse(0));
        }
        
        private static IOFunction<HttpEntity, ByteArrayOutputStream> BYTES
                = entity -> {
                    var sink = new ByteArrayOutputStream();
                    entity.getContent().transferTo(sink);
                    return sink;
                };
        
        private static IOFunction<HttpEntity, byte[]> BYTE_ARR
                = BYTES.andThen(ByteArrayOutputStream::toByteArray);
        
        private static IOFunction<HttpEntity, String> STRING
                = BYTES.andThen(stream -> stream.toString(US_ASCII));
    }
    
    private static final class Jetty extends HttpClientFacade {
        Jetty(int port) {
            super(port);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws InterruptedException, ExecutionException, TimeoutException
        {
            return executeReq("GET", path, ver, null,
                    ContentResponse::getContent);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws InterruptedException, ExecutionException, TimeoutException
        {
            return executeReq("GET", path, ver, null,
                    ContentResponse::getContentAsString);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version ver, String body
            ) throws ExecutionException, InterruptedException, TimeoutException
        {
            return executeReq("POST", path, ver,
                    new StringRequestContent(body),
                    ContentResponse::getContentAsString);
        }
        
        @Override
        public ResponseFacade<Void> postBytesAndReceiveEmpty(
                String path, HttpConstants.Version ver, byte[] body)
                throws ExecutionException, InterruptedException, TimeoutException
        {
            return executeReq("POST", path, ver,
                    new BytesRequestContent(body),
                    ContentResponse::getContent).assertEmpty();
        }
        
        @Override
        public ResponseFacade<String> postChunksAndReceiveText(
                String path, byte[] firstChunk, byte[]... more)
                throws ExecutionException, InterruptedException, TimeoutException
        {
            return executeReq("POST", path, HTTP_1_1,
                    new InputStreamRequestContent(newInputStream(firstChunk, more)),
                    ContentResponse::getContentAsString);
        }
        
        private <B> ResponseFacade<B> executeReq(
                String method, String path,
                HttpConstants.Version ver,
                org.eclipse.jetty.client.api.Request.Content reqBody,
                Function<? super ContentResponse, ? extends B> rspBodyConverter
                ) throws ExecutionException, InterruptedException, TimeoutException
        {
            var cli = new org.eclipse.jetty.client.HttpClient();
            try {
                cli.start();
            } catch (Exception e) { // <-- lol
                throw new RuntimeException(e);
            }
            ContentResponse rsp;
            try {
                var req = cli.newRequest(withBase(path))
                           .method(method)
                           .version(toJettyVersion(ver))
                           .body(reqBody);
                copyClientHeaders((k, v) ->
                        req.headers(h -> h.add(k, v)));
                rsp = req.send();
            } finally {
                try {
                    cli.stop();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return ResponseFacade.fromJetty(rsp, rspBodyConverter);
        }
        
        private static org.eclipse.jetty.http.HttpVersion
                toJettyVersion(HttpConstants.Version ver) {
            return org.eclipse.jetty.http.HttpVersion
                    .fromString(ver.toString());
        }
    }
    
    private static final class Reactor extends HttpClientFacade {
        Reactor(int port) {
            super(port);
        }
        
        // On Reactor, the default implementation in HttpClientFacade returns NULL (!), causing NPE.
        // It is conceivable that we'll have to drop support for Reactor, being ridiculous as it is.
        @Override
        public ResponseFacade<Void> getEmpty(String path, HttpConstants.Version ver) {
            return GET(path, ver, null).assertEmpty();
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver) {
            return GET(path, ver, ByteBufMono::asByteArray);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver) {
            return GET(path, ver, ByteBufMono::asString);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version ver, String body)
        {
            return executeReq(
                    io.netty.handler.codec.http.HttpMethod.POST, path, ver,
                    // Erm, yeah, it gets worse...
                    ByteBufFlux.fromString(Mono.just(body)),
                    ByteBufMono::asString);
        }
        
        @Override
        public ResponseFacade<Void> postBytesAndReceiveEmpty(
                String path, HttpConstants.Version ver, byte[] body)
        {
            return executeReq(
                    io.netty.handler.codec.http.HttpMethod.POST, path, ver,
                    // "from inbound" lol
                    ByteBufFlux.fromInbound(Mono.just(body)),
                    ByteBufMono::asByteArray)
                        .assertEmpty();
        }
        
        @Override
        public ResponseFacade<String> postChunksAndReceiveText(
                String path, byte[] firstChunk, byte[]... more) {
            return executeReq(
                    io.netty.handler.codec.http.HttpMethod.POST, path, HTTP_1_1,
                    ByteBufFlux.fromInbound(
                            Flux.fromStream(Streams.stream(firstChunk, more))),
                    ByteBufMono::asString);
        }
        
        private <B> ResponseFacade<B> GET(
                String path, HttpConstants.Version ver,
                Function<? super ByteBufMono, ? extends Mono<B>> rspBodyConverter)
        {
            return executeReq(
                    io.netty.handler.codec.http.HttpMethod.GET, path, ver,
                    null,
                    rspBodyConverter);
        }
        
        private <B> ResponseFacade<B> executeReq(
                io.netty.handler.codec.http.HttpMethod method,
                String path, HttpConstants.Version ver,
                org.reactivestreams.Publisher<? extends ByteBuf> reqBody,
                Function<? super ByteBufMono, ? extends Mono<B>> rspBodyConverter)
        {
            var client = reactor.netty.http.client.HttpClient.create()
                    .protocol(toReactorVersion(ver));
            
            for (var entry : clientHeaders()) {
                var name = entry.getKey();
                for (var value : entry.getValue()) {
                    // Yup, one "consume" a mutable object, but... also returns a new client
                    // (who doesn't love a good surprise huh!)
                    client = client.headers(h -> h.add(name, value));
                }
            }
            
            // "uri() should be invoked before request()" says the JavaDoc.
            // Except that doesn't compile lol
            HttpClient.RequestSender sender
                    = client.request(method)
                            .uri(withBase(path));
            
            // Yes, wildcard
            ResponseReceiver<?> receiver;
            if (reqBody != null) {
                // Because of this
                // (oh, and nothing was quote unquote "sent", btw)
                receiver = sender.send(reqBody);
            } else {
                // Sender is actually a... *drumroll* ....
                receiver = sender;
            }
            
            // Okay seriously, someone give them an award in API design......
            // (compare this method impl with everyone else; even Jetty that
            //  throws Exception is a better option lol)
            if (rspBodyConverter == null) {
                return retype(receiver.response().map(rsp ->
                        ResponseFacade.fromReactor(rsp, null)).block());
            }
            return receiver.responseSingle((head, firstBody) ->
                rspBodyConverter.apply(firstBody).map(anotherBody ->
                        ResponseFacade.fromReactor(head, anotherBody)))
                .block();
        }
        
        private static HttpProtocol toReactorVersion(HttpConstants.Version ver) {
            return switch (ver) {
                case HTTP_1_1 -> HttpProtocol.HTTP11;
                case HTTP_2 -> HttpProtocol.H2;
                default -> throw new IllegalArgumentException("No mapping.");
            };
        }
    }
    
    /**
     * An HTTP response API.<p>
     * 
     * Delegates all operations to the underlying client's response
     * implementation.<p>
     * 
     * This class does not cache anything, and if possible, it'll delegate to
     * the client's implementation lazily. The purpose is to have problems occur
     * with a nice stack trace exactly when and where it is a problem, i.e. in
     * response assert statements.<p>
     * 
     * {@code hashCode} and {@code equals} throws
     * {@link UnsupportedOperationException}. This is due to headers being
     * processed differently by clients; some keep header name casing as
     * received, some lower-case the names. Some may even change the casing of
     * values.
     * 
     * @param <B> body type
     */
    public static final class ResponseFacade<B> {
        
        static <B> ResponseFacade<B> fromJDK(java.net.http.HttpResponse<? extends B> jdk) {
            return new ResponseFacade<>(
                    () -> HttpConstants.Version.valueOf(jdk.version().name()).toString(),
                    jdk::statusCode,
                    unsupported(),
                    () -> new DefaultContentHeaders(new LinkedHashMap<>(jdk.headers().map()), false),
                    jdk::body,
                    unsupportedIO());
        }
        
        static <B> ResponseFacade<B> fromOkHttp(okhttp3.Response okhttp, B body) {
            return new ResponseFacade<>(
                    () -> okhttp.protocol().toString().toUpperCase(ROOT),
                    okhttp::code,
                    okhttp::message,
                    () -> new DefaultContentHeaders(new LinkedHashMap<>(okhttp.headers().toMultimap()), false),
                    () -> body,
                    supplyOurHeadersTypeIO(() ->
                        StreamSupport.stream(okhttp.trailers().spliterator(), false),
                        Pair::component1,
                        Pair::component2));
        }
        
        static <B> ResponseFacade<B> fromApache(
                ClassicHttpResponse apache, B body) {
            return new ResponseFacade<>(
                    () -> apache.getVersion().toString(),
                    apache::getCode,
                    apache::getReasonPhrase,
                    supplyOurHeadersType(() ->
                        stream(apache.getHeaders()),
                        NameValuePair::getName,
                        NameValuePair::getValue),
                    () -> body,
                    supplyOurHeadersTypeIO(() ->
                        apache.getEntity().getTrailers().get().stream(),
                        NameValuePair::getName,
                        NameValuePair::getValue));
        }
        
        static <B> ResponseFacade<B> fromJetty(
                org.eclipse.jetty.client.api.ContentResponse jetty,
                Function<? super ContentResponse, ? extends B> bodyConverter)
        {
            return new ResponseFacade<>(
                    () -> jetty.getVersion().toString(),
                    jetty::getStatus,
                    jetty::getReason,
                    supplyOurHeadersType(() -> jetty.getHeaders().stream(),
                            org.eclipse.jetty.http.HttpField::getName,
                            org.eclipse.jetty.http.HttpField::getValue),
                    () -> bodyConverter.apply(jetty),
                    unsupportedIO());
        }
        
        static <B> ResponseFacade<B> fromReactor(HttpClientResponse reactor, B body) {
            return new ResponseFacade<>(
                    () -> reactor.version().toString(),
                    () -> reactor.status().code(),
                    () -> reactor.status().reasonPhrase(),
                    supplyOurHeadersType(
                        () -> reactor.responseHeaders().entries().stream(),
                        Map.Entry::getKey,
                        Map.Entry::getValue),
                    () -> body,
                    supplyOurHeadersTypeIO(() ->
                        StreamSupport.stream(reactor.trailerHeaders().block(ofSeconds(2)).spliterator(), false),
                        Map.Entry::getKey,
                        Map.Entry::getValue));
        }
        
        private static <T> Supplier<BetterHeaders> supplyOurHeadersType(
                Supplier<Stream<T>> fromNativeHeaders,
                Function<? super T, String> name, Function<? super T, String> value) {
            return () -> {
                try {
                    return supplyOurHeadersTypeIO(fromNativeHeaders::get, name, value).get();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
        }
        
        private static <T> IOSupplier<BetterHeaders> supplyOurHeadersTypeIO(
                IOSupplier<Stream<T>> fromNativeHeaders,
                Function<? super T, String> name, Function<? super T, String> value) {
            return () -> {
                var pairs = fromNativeHeaders.get()
                        .<String>mapMulti((h, sink) -> {
                            sink.accept(name.apply(h));
                            sink.accept(value.apply(h)); })
                        .toArray(String[]::new);
                return new DefaultContentHeaders(linkedHashMap(pairs), false);
            };
        }
        
        private static <T> Supplier<T> unsupported() {
            return () -> { throw new UnsupportedOperationException(); };
        }
        
        private static <T> IOSupplier<T> unsupportedIO() {
            return () -> { throw new UnsupportedOperationException(); };
        }
        
        private final Supplier<String> version;
        private final IntSupplier statusCode;
        private final Supplier<String> reasonPhrase;
        private final Supplier<BetterHeaders> headers;
        private final Supplier<? extends B> body;
        private final IOSupplier<BetterHeaders> trailers;
        
        private ResponseFacade(
                Supplier<String> version,
                IntSupplier statusCode,
                Supplier<String> reasonPhrase,
                Supplier<BetterHeaders> headers,
                Supplier<? extends B> body,
                IOSupplier<BetterHeaders> trailers)
        {
            this.version      = version;
            this.statusCode   = statusCode;
            this.reasonPhrase = reasonPhrase;
            this.headers      = headers;
            this.body         = body;
            this.trailers     = trailers;
        }
        
        /**
         * Returns the HTTP version.
         * 
         * @return the HTTP version
         */
        public String version() {
            return version.get();
        }
        
        /**
         * Returns the response status-code.
         * 
         * @return status-code
         */
        public int statusCode() {
            return statusCode.getAsInt();
        }
        
        /**
         * Returns the response reason-phrase.
         * 
         * @return reason-phrase
         * 
         * @throws UnsupportedOperationException
         *             if the underlying client does not support this operation
         */
        public String reasonPhrase() {
            return reasonPhrase.get();
        }
         
        /**
         * Returns the response headers.
         * 
         * @return headers
         */
        public BetterHeaders headers() {
            return headers.get();
        }
        
        /**
         * Returns the response body.
         * 
         * @return body
         */
        public B body() {
            // TODO: Throw Unsupported if empty
            return body.get();
        }
        
        /**
         * Returns the trailers.
         * 
         * @return the trailers
         * @throws IOException if an I/O error occurs (only by OkHttp)
         */
        public BetterHeaders trailers() throws IOException {
            return trailers.get();
        }
        
        ResponseFacade<Void> assertEmpty() {
            B b = body();
            if (b == null) {
                return retype(this);
            } else if (b instanceof byte[]) {
                assertThat((byte[]) b).isEmpty();
            } else if (b instanceof CharSequence) {
                assertThat((CharSequence) b).isEmpty();
            } else {
                throw new AssertionError("Unexpected type: " + b.getClass());
            }
            return retype(this);
        }
        
        @Override
        public int hashCode() {
            throw new UnsupportedOperationException();
        }
        
        @Override
        public boolean equals(Object other) {
            throw new UnsupportedOperationException();
        }
    }
    
    private static <T, U> T retype(U u) {
        @SuppressWarnings("unchecked")
        T t = (T) u;
        return t;
    }
    
    private static InputStream newInputStream(byte[] first, byte[]... more) {
        var byteArrays = toList(first, more);
        var byteBuffer = allocate(byteArrays.stream().mapToInt(b -> b.length).sum());
        byteArrays.forEach(byteBuffer::put);
        return new ByteArrayInputStream(byteBuffer.array());
    }
}