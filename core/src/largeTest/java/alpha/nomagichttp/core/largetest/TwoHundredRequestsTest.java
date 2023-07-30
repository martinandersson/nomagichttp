package alpha.nomagichttp.core.largetest;

import alpha.nomagichttp.core.largetest.util.AbstractLargeRealTest;
import alpha.nomagichttp.core.largetest.util.DataUtils;
import alpha.nomagichttp.testutil.functional.HttpClientFacade;
import alpha.nomagichttp.testutil.functional.HttpClientFacade.ResponseFacade;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.Channel;
import java.util.HashMap;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.functional.Constants.TEST_CLIENT;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.REACTOR;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.createAll;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.createAllExceptFor;
import static java.util.Comparator.comparingDouble;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

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
final class TwoHundredRequestsTest extends AbstractLargeRealTest
{
    // One hundred small + big bodies = 200 requests
    private static final int REQUESTS_PER_SIZE = 100;
    
    private static final Map<String, LongStream.Builder> STATS = new HashMap<>();
    
    private static Channel CONN;
    
    @BeforeAll
    void addHandler() throws IOException {
        // Echo the body
        server().add("/", POST().apply(
                req -> text(req.body().toText())));
    }
    
    @AfterAll
    void closeConn() throws IOException {
        if (CONN != null) {
            CONN.close();
        }
    }
    
    @Nested
    @TestInstance(PER_CLASS)
    class Small {
        @ParameterizedTest(name = TEST_CLIENT)
        @MethodSource("bodies")
        void testClient(String requestBody, TestInfo info) throws Exception {
            postAndAssertResponseUsingTestClient(requestBody, info);
        }
        
        @ParameterizedTest(name = "{1}") // e.g. "Small/Apache"
        @MethodSource("bodiesAndClient")
        void compatibility(
                String requestBody, HttpClientFacade client, TestInfo info)
                throws Exception {
            postAndAssertResponseUsing(requestBody, client, info);
        }
        
        private static Stream<String> bodies() {
            return requestBodies(REQUESTS_PER_SIZE, 0, 10);
        }
        
        private Stream<Arguments> bodiesAndClient() {
            // bodies() may return a 0-length body, and Reactor (surprise!)
            // will then return a null response causing NPE.
            // TODO: Either remove this phenomenally shitty client altogether or hack
            //       the implementation just as we had to hack HttpClientFacade.getEmpty().
            var clients = createAllExceptFor(serverPort(), REACTOR);
            return mapToBodyAndClientArgs(clients, Small::bodies);
        }
    }
    
    @Nested
    @TestInstance(PER_CLASS)
    class Big {
        /*
         * The default name would have been the requestBody argument, which is a
         * super huge string. This renders the HTML report file 50 MB large and
         * extremely slow to open.
         * 
         * @DisplayName normally changes the "test name" but has no effect at
         * all for parameterized tests. So one must use the name attribute
         * instead.
         * 
         * https://github.com/gradle/gradle/issues/5975
         */
        @ParameterizedTest(name = TEST_CLIENT)
        @MethodSource("bodies")
        void testClient(String requestBody, TestInfo info) throws Exception {
            postAndAssertResponseUsingTestClient(requestBody, info);
        }
        
        @ParameterizedTest(name = "{1}")
        @MethodSource("bodiesAndClient")
        void compatibility(
                String requestBody, HttpClientFacade client, TestInfo info)
                throws Exception {
            postAndAssertResponseUsing(requestBody, client, info);
        }
        
        private static Stream<String> bodies() {
            // ChannelReader#BUFFER_SIZE
            final int bufSize  = 512,
                      oneThird = bufSize / 3,
                      tenTimes = bufSize * 10;
            return requestBodies(REQUESTS_PER_SIZE, oneThird, tenTimes);
        }
        
        private Stream<Arguments> bodiesAndClient() {
            var clients = createAll(serverPort());
            return mapToBodyAndClientArgs(clients, Big::bodies);
        }
    }
    
    private void postAndAssertResponseUsingTestClient(
            String body, TestInfo info) throws Exception
    {
        if (CONN == null) {
            CONN = client().openConnection();
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
    
    private static <R> R takeTime(
            TestInfo info, ResponseSupplier<R> rsp) throws Exception {
        long before = System.nanoTime();
        R r = rsp.get();
        long after = System.nanoTime();
        // E.g. "Small/TestClient"
        String name = info.getTestClass().get().getSimpleName() + "/" +
                      info.getDisplayName();
        STATS.compute(name, (k, b) -> {
            if (b == null) {
                b = LongStream.builder();
            }
            b.accept(after - before);
            return b;
        });
        return r;
    }
    
    @AfterAll
    static void statsDumpEverything() {
        statsDumpGroup(Small.class);
        statsDumpGroup(Big.class);
    }
    
    private static void statsDumpGroup(Class<?> grp) {
        record Tuple(String test, LongSummaryStatistics result) {
            public String toString() {
                return result.toString().replace(
                        LongSummaryStatistics.class.getSimpleName(), test);
            }
        }
        STATS.entrySet()
             .stream()
             .filter(e -> e.getKey().startsWith(grp.getSimpleName()))
             .map(e -> {
                 var res = e.getValue()
                            .build()
                            .map(NANOSECONDS::toMillis)
                            // Skip first (possible connection setup)
                            .skip(1)
                            .summaryStatistics();
                 return new Tuple(e.getKey(), res);
             })
             .sorted(comparingDouble(t -> t.result().getAverage()))
             .forEach(System.out::println);
    }
    
    @FunctionalInterface
    interface ResponseSupplier<R> {
        R get() throws Exception;
    }
}
