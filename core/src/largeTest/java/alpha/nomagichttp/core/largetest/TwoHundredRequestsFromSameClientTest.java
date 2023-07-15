package alpha.nomagichttp.core.largetest;

import alpha.nomagichttp.core.largetest.util.AbstractLargeRealTest;
import alpha.nomagichttp.testutil.functional.HttpClientFacade;
import alpha.nomagichttp.testutil.functional.HttpClientFacade.ResponseFacade;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.Channel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.REACTOR;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.createAll;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.createAllExceptFor;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * POSTs one hundred small requests and one hundred big requests using the same
 * client.<p>
 * 
 * The purpose of the test is to ensure successful communication of
 * differently-sized requests, over the same connection.<p>
 * 
 * For the NoMagicHTTP's TestClient, all requests are guaranteed to be sent over
 * the same connection. Other clients do not expose an API to handle the
 * connection or even query its characteristics, but they should nonetheless all
 * be using a persistent connection (see {@link HttpClientFacade}).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: A similar test, but with loads of concurrent clients
// TODO: Assert persistent connections (e.g. by counting children on the server)
class TwoHundredRequestsFromSameClientTest extends AbstractLargeRealTest
{
    // One hundred small bodies + one hundred big bodies = 200 requests
    private static final int REQUESTS_PER_BATCH = 100;
    
    private static final Map<String, LongStream.Builder> STATS = new HashMap<>();
    
    private Channel conn;
    @BeforeAll
    void addHandler() throws IOException {
        // Echo the body
        server().add("/", POST().apply(
                req -> text(req.body().toText())));
    }
    
    @AfterAll
    void closeConn() throws IOException {
        if (conn != null) {
            conn.close();
        }
    }
    
    @AfterAll
    void statsDump() {
        // This guy is so freakin' fast we have to do microseconds lol
        var tc = STATS.remove("small/TestClient");
        if (tc != null) {
            System.out.println("small/TestClient (μs): " +
                    // Skip first (possible connection setup)
                    tc.build().map(NANOSECONDS::toMicros).skip(1).summaryStatistics());
        }
        STATS.forEach((test, elapsed) ->
            System.out.println(test + " (ms): " + elapsed
                .build().map(NANOSECONDS::toMillis).skip(1).summaryStatistics()));
    }
    
    @ParameterizedTest(name = "small/TestClient")
    @MethodSource("smallBodies")
    void small(String requestBody, TestInfo info) throws Exception {
        postAndAssertResponseUsingTestClient(requestBody, info);
    }
    
    // Default name would have been msg argument, which is a super huge string!
    // This renders the html report file 50 MB large and extremely slow to open.
    // @DisplayName normally changes the "test name" but has no effect at all
    // for parameterized tests. So we must use the name attribute instead.
    // https://github.com/gradle/gradle/issues/5975
    @ParameterizedTest(name = "big/TestClient")
    @MethodSource("bigBodies")
    void big(String requestBody, TestInfo info) throws Exception {
        postAndAssertResponseUsingTestClient(requestBody, info);
    }
    
    @ParameterizedTest(name = "small/{1}") // e.g. "small/Apache"
    @MethodSource("smallBodiesAndClient")
    void small_compatibility(String requestBody, HttpClientFacade client, TestInfo info)
            throws Exception
    {
        postAndAssertResponseUsing(requestBody, client, info);
    }
    
    @ParameterizedTest(name = "big/{1}")
    @MethodSource("bigBodiesAndClient")
    void big_compatibility(String requestBody, HttpClientFacade client, TestInfo info)
            throws Exception
    {
        postAndAssertResponseUsing(requestBody, client, info);
    }
    
    private void postAndAssertResponseUsingTestClient(String body, TestInfo info) throws Exception {
        if (conn == null) {
            conn = client().openConnection();
        }
        
        final String b = body + "EOM";
        
        String rsp  = takeTime(info, () -> client().writeReadTextUntil(
            "POST / HTTP/1.1"                         + CRLF +
            "User-Agent: TestClient"                  + CRLF +
            "Content-Length: " + b.length()           + CRLF + CRLF +
            
            b, "EOM"));
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: " + b.length()           + CRLF + CRLF +
            
            b);
    }
    
    private void postAndAssertResponseUsing(
            String body, HttpClientFacade client, TestInfo info)
            throws Exception
    {
        ResponseFacade<String> rsp = takeTime(info, () ->
                client.postAndReceiveText("/", HTTP_1_1, body));
        assertThat(rsp.version()).isEqualTo("HTTP/1.1");
        assertThat(rsp.statusCode()).isEqualTo(200);
        assertThat(rsp.body()).isEqualTo(body);
    }
    
    private static Stream<String> smallBodies() {
        return requestBodies(REQUESTS_PER_BATCH, 0, 10);
    }
    
    private static Stream<String> bigBodies() {
        // ChannelReader#BUFFER_SIZE
        final int bufSize  = 512,
                  oneThird = bufSize / 3,
                  tenTimes = bufSize * 10;
        return requestBodies(REQUESTS_PER_BATCH, oneThird, tenTimes);
    }
    
    private Stream<Arguments> smallBodiesAndClient() {
        // smallBodies() may return a 0-length body, and Reactor (surprise!)
        // will then return a null response causing NPE.
        // TODO: Either remove this phenomenally shitty client altogether or hack
        //       the implementation just as we had to hack HttpClientFacade.getEmpty().
        var clients = createAllExceptFor(serverPort(), REACTOR);
        return mapToBodyAndClientArgs(clients,
                TwoHundredRequestsFromSameClientTest::smallBodies);
    }
    
    private Stream<Arguments> bigBodiesAndClient() {
        var clients = createAll(serverPort());
        return mapToBodyAndClientArgs(clients,
                TwoHundredRequestsFromSameClientTest::bigBodies);
    }
    
    private static Stream<Arguments> mapToBodyAndClientArgs(
            Stream<HttpClientFacade> clients, Supplier<Stream<String>> batch) {
        return clients.flatMap(c ->
                batch.get().map(body -> Arguments.of(body, c)));
    }
    
    /**
     * Builds a stream that generates request bodies of various lengths.
     * 
     * @param n stream length
     * @param minLen minimum body length (inclusive)
     * @param maxLen maximum body length (inclusive)
     * 
     * @return request bodies (just text)
     */
    private static Stream<String> requestBodies(int n, int minLen, int maxLen) {
        Supplier<String> s = () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            return DataUtils.text(r.nextInt(minLen, maxLen + 1));
        };
        return Stream.generate(s).limit(n);
    }
    
    private static <R> R takeTime(TestInfo info, ResponseSupplier<R> rsp) throws Exception {
        long before = System.nanoTime();
        R r = rsp.get();
        long after = System.nanoTime();
        STATS.compute(info.getDisplayName(), (k, b) -> {
            if (b == null) {
                b = LongStream.builder();
            }
            b.accept(after - before);
            return b;
        });
        return r;
    }
    
    @FunctionalInterface
    interface ResponseSupplier<R> {
        R get() throws Exception;
    }
}