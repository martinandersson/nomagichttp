package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.ProtocolVersion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.net.http.HttpClient.Version;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.Arrays.stream;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A HTTP client API that delegates to another {@link Implementation
 * implementation}.<p>
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
 *       Response{@literal <}String{@literal >} response = client.getText("/", HTTP_1_1);
 *       assertThat(response.statusCode())...
 *   }
 * </pre>
 * 
 * Let's be honest here. The clients used are expected to not expose an API for
 * user-control of the connection, and to add insult to injury, they are also
 * expected to not documented at all what the life-cycle of the connection is.
 * Hence, the facade implementation can do no better either. It will, however,
 * attempt to create the client eagerly (in the constructor) and reuse the same
 * object across the facade's exchange-method calls. The facade is not
 * closeable, but if the the client is - which is a hint that the client object
 * itself represents the connection - then the client will be created and closed
 * for each exchange-executing method called.<p>
 * 
 * So, no guarantees can be made about the connection. Likely, it will live in a
 * client-specific connection pool until timeout. Furthermore, attempts to hack
 * the connection may fail. For example, the JDK client will throw an {@code
 * IllegalArgumentException} if the "Connection: close" header is set.<p>
 * 
 * But, if the connection is never closed, then a test class extending {@code
 * AbstractRealTest} will timeout after each test when the superclass stops the
 * server and gracefully awaits child channel closures. To fix this problem, the
 * test ought to close the child from the server-installed request handler.<p>
 * 
 * This class is not thread-safe and does not implement {@code hashCode} or
 * {@code equals}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
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
        APACHE (Apache::new);
        
        // TODO:
        //   Jetty
        //   Reactor-Netty
        
        private final IntFunction<HttpClientFacade> factory;
        
        Implementation(IntFunction<HttpClientFacade> f) {
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
     * Constructs a {@code HttpClientFacade}.
     * 
     * @param port of server
     */
    protected HttpClientFacade(int port) {
         this.port = port;
         this.headers = Map.of();
    }
    
    /**
     * Add a header.
     * 
     * @param name of header
     * @param value of header
     * @return this for chaining/fluency
     */
    public final HttpClientFacade addHeader(String name, String value) {
        requireNonNull(name);
        requireNonNull(value);
        if (headers.isEmpty()) { // i.e. == Map.of()
            headers = new LinkedHashMap<>();
        }
        headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
        return this;
    }
    
    /**
     * Create a URI with "http://localhost:{port}" prefixed to the specified
     * {@code path}.
     * 
     * @param path of server resource
     * @return a URI
     */
    protected final URI withBase(String path) {
        return URI.create("http://localhost:" + port + path);
    }
    
    /**
     * Copy all headers contained in this class into the given sink.
     * 
     * @param sink target
     */
    protected void copyHeaders(BiConsumer<String, String> sink) {
        headers.forEach((name, values) ->
                values.forEach(v -> sink.accept(name, v)));
    }
    
    /**
     * Execute a GET request expecting bytes in the response body.
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
     * @throws TimeoutException
     *             if an otherwise asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract Response<byte[]> getBytes(String path, HttpConstants.Version version)
            throws IOException, InterruptedException, TimeoutException;
    
    /**
     * Execute a GET request expecting text in the response body.<p>
     * 
     * What charset to use for decoding is for the client implementation to
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
     * @throws TimeoutException
     *             if an otherwise asynchronous operation times out
     *             (timeout duration not specified)
     */
    public abstract Response<String> getText(String path, HttpConstants.Version version)
            throws IOException, InterruptedException, TimeoutException;
    
    private static class JDK extends HttpClientFacade {
        JDK(int port) {
            super(port);
        }
        
        @Override
        public Response<byte[]> getBytes(String path, HttpConstants.Version version)
                throws IOException, InterruptedException
        {
            return get(path, version, ofByteArray());
        }
    
        @Override
        public Response<String> getText(String path, HttpConstants.Version version)
                throws IOException, InterruptedException
        {
            return get(path, version, ofString());
        }
        
        private <B> Response<B> get(
                String path, HttpConstants.Version version, HttpResponse.BodyHandler<B> converter)
                throws IOException, InterruptedException
        {
            var b = HttpRequest.newBuilder()
                    .version(toJDK(version))
                    .GET().uri(withBase(path));
            
            copyHeaders(b::header);
            
            var rsp = HttpClient.newHttpClient()
                    .send(b.build(), converter);
            
            return Response.of(rsp);
        }
        
        private static Version toJDK(HttpConstants.Version v) {
            final Version jdk;
            switch (v) {
                case HTTP_0_9:
                case HTTP_1_0:
                case HTTP_3:
                    throw new IllegalArgumentException("Not supported.");
                case HTTP_1_1:
                    jdk = Version.HTTP_1_1;
                    break;
                case HTTP_2:
                    jdk = Version.HTTP_2;
                    break;
                default:
                    throw new IllegalArgumentException("No mapping.");
            }
            return jdk;
        }
    }
    
    private static class OkHttp extends HttpClientFacade {
        OkHttp(int port) {
            super(port);
        }
        
        @Override
        public Response<byte[]> getBytes(String path, HttpConstants.Version version)
                throws IOException
        {
            return get(path, version, ResponseBody::bytes);
        }
        
        @Override
        public Response<String> getText(String path, HttpConstants.Version version)
                throws IOException
        {
            return get(path, version, ResponseBody::string);
        }
        
        private <B> Response<B> get(
                String path, HttpConstants.Version version,
                IOFunction<? super ResponseBody, ? extends B> converter)
                throws IOException
        {
            OkHttpClient c = new OkHttpClient.Builder()
                    .protocols(List.of(toSquare(version)))
                    .build();
            
            Request req = new Request.Builder()
                    .url(withBase(path).toURL())
                    .build();
            
            // No close callback from our Response type, so must consume eagerly
            okhttp3.Response rsp = c.newCall(req).execute();
            B b;
            try (rsp) {
                b = converter.apply(rsp.body());
            }
            return Response.of(rsp, b);
        }
        
        private static Protocol toSquare(HttpConstants.Version v) {
            final Protocol square;
            switch (v) {
                case HTTP_0_9:
                case HTTP_3:
                    throw new IllegalArgumentException("Not supported.");
                case HTTP_1_0:
                    square = Protocol.HTTP_1_0;
                    break;
                case HTTP_1_1:
                    square = Protocol.HTTP_1_1;
                    break;
                case HTTP_2:
                    square = Protocol.HTTP_2;
                    break;
                default:
                    throw new IllegalArgumentException("No mapping.");
            }
            return square;
        }
    }
    
    private static class Apache extends HttpClientFacade {
        Apache(int port) {
            super(port);
        }
        
        @Override
        public Response<byte[]> getBytes(String path, HttpConstants.Version version)
                throws IOException, InterruptedException, TimeoutException
        {
            return get(path, version, SimpleHttpResponse::getBodyBytes);
        }
        
        @Override
        public Response<String> getText(String path, HttpConstants.Version version)
                throws IOException, InterruptedException, TimeoutException
        {
            return get(path, version, SimpleHttpResponse::getBodyText);
        }
        
        private <B> Response<B> get(
                String path, HttpConstants.Version version,
                Function<? super SimpleHttpResponse, ? extends B> converter)
                throws IOException, InterruptedException, TimeoutException
        {
            var req =  SimpleRequestBuilder.get(withBase(path))
                    .setVersion(toApacheVersion(version))
                    .build();
            
            try (CloseableHttpAsyncClient c = HttpAsyncClients.createDefault()) {
                // Must "start" first, otherwise
                //     java.util.concurrent.CancellationException: Request execution cancelled
                c.start();
                try {
                    var rsp = c.execute(req, null).get(3, SECONDS);
                    return Response.of(rsp, converter.apply(rsp));
                } catch (ExecutionException e) {
                    throw new CompletionException(e);
                }
            }
        }
        
        private static ProtocolVersion toApacheVersion(HttpConstants.Version v) {
            return HttpVersion.get(v.major(), v.minor().orElse(0));
        }
    }
    
    /**
     * A HTTP response API.<p>
     * 
     * Delegates all operations to the underlying client's response
     * implementation.
     * 
     * @param <B> body type
     */
    public static final class Response<B> {
        
        static <B> Response<B> of(java.net.http.HttpResponse<? extends B> jdk) {
            Supplier<String> version = () -> HttpConstants.Version.valueOf(jdk.version().name()).toString(),
                             phrase  = () -> {throw new UnsupportedOperationException();};
            return new Response<>(
                    version,
                    jdk::statusCode,
                    phrase,
                    jdk::headers,
                    jdk::body);
        }
        
        static <B> Response <B> of(okhttp3.Response okhttp, B body) {
            Supplier<HttpHeaders> headers = () -> Headers.of(okhttp.headers().toMultimap());
            return new Response<>(
                    () -> okhttp.protocol().toString().toUpperCase(ROOT),
                    okhttp::code,
                    okhttp::message,
                    headers, () -> body);
        }
        
        static <B> Response <B> of(SimpleHttpResponse apache, B body) {
            Supplier<HttpHeaders> headers = () -> {
                var exploded = stream(apache.getHeaders())
                        .flatMap(h -> Stream.of(h.getName(), h.getValue()))
                        .toArray(String[]::new);
                
                return Headers.of(exploded);
            };
            return new Response<>(
                    () -> apache.getVersion().toString(),
                    apache::getCode,
                    apache::getReasonPhrase,
                    headers,
                    () -> body);
        }
        
        private final Supplier<String> version;
        private final IntSupplier statusCode;
        private final Supplier<String> reasonPhrase;
        private final Supplier<HttpHeaders> headers;
        private final Supplier<? extends B> body;
        
        private Response(
                Supplier<String> version,
                IntSupplier statusCode,
                Supplier<String> reasonPhrase,
                Supplier<HttpHeaders> headers,
                Supplier<? extends B> body)
        {
            this.version = version;
            this.statusCode   = statusCode;
            this.reasonPhrase = reasonPhrase;
            this.headers      = headers;
            this.body         = body;
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
        public HttpHeaders headers() {
            return headers.get();
        }
        
        /**
         * Returns the response body.
         * 
         * @return body
         */
        public B body() {
            return body.get();
        }
    }
}