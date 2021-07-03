package alpha.nomagichttp.largetest;

import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.testutil.AbstractLargeRealTest;
import alpha.nomagichttp.testutil.HttpClientFacade;
import alpha.nomagichttp.testutil.HttpClientFacade.ResponseFacade;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.Channel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.testutil.HttpClientFacade.Implementation.APACHE;
import static alpha.nomagichttp.testutil.HttpClientFacade.Implementation.REACTOR;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * POSTs one hundred small requests and one hundred big requests using the same
 * client. For the NoMagicHTTP's TestClient, all requests are also guaranteed to
 * be sent over the same connection.<p>
 * 
 * The purpose is to ensure that server-side bytebuffer pooling and constant
 * switching of Flow.Subscriber works as expected without data corruption.<p>
 * 
 * TODO: A similar test, but with loads of concurrent clients, each with their
 * own unique data stream.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class TwoHundredRequestsFromSameClientTest extends AbstractLargeRealTest
{
    private static final int REQUESTS_PER_BATCH = 100;
    
    private Channel conn;
    
    @BeforeAll
    void addHandler() throws IOException {
        server().add("/", POST().apply(req ->
                req.body().toText().thenApply(txt -> {
                    var rsp = Responses.text(txt);
                    if (!req.headerContains("User-Agent", "TestClient")) {
                        rsp = setMustCloseAfterWrite(rsp);
                    }
                    return rsp;
                })));
        conn = client().openConnection();
    }
    
    @AfterAll
    void closeConn() throws IOException {
        conn.close();
    }
    
    @ParameterizedTest(name = "small/TestClient")
    @MethodSource("smallBodies")
    void small(String requestBody) throws IOException {
        postAndAssertResponseUsingTestClient(requestBody);
    }
    
    // Default name would have been msg argument, which is a super huge string!
    // This renders the html report file 50 MB large and extremely slow to open.
    // @DisplayName normally changes the "test name" but has no effect at all
    // for parameterized tests. So we must use the name attribute instead.
    // https://github.com/gradle/gradle/issues/5975
    @ParameterizedTest(name = "big/TestClient")
    @MethodSource("bigBodies")
    void big(String requestBody) throws IOException {
        postAndAssertResponseUsingTestClient(requestBody);
    }
    
    @ParameterizedTest(name = "small/{1}") // e.g. "small/Apache"
    @MethodSource("smallBodiesAndClient")
    void small_compatibility(String requestBody, HttpClientFacade client)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        postAndAssertResponseUsing(requestBody, client);
    }
    
    @ParameterizedTest(name = "big/{1}")
    @MethodSource("bigBodiesAndClient")
    void big_compatibility(String requestBody, HttpClientFacade client)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        postAndAssertResponseUsing(requestBody, client);
    }
    
    private void postAndAssertResponseUsingTestClient(String body) throws IOException {
        final String b = body + "EOM";
        
        String rsp  = client().writeReadTextUntil(
            "POST / HTTP/1.1"                         + CRLF +
            "User-Agent: TestClient"                  + CRLF +
            "Content-Length: " + b.length()           + CRLF + CRLF +
            
            b, "EOM");
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: " + b.length()           + CRLF + CRLF +
            
            b);
    }
    
    private void postAndAssertResponseUsing(String body, HttpClientFacade client)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        ResponseFacade<String> rsp = client.postAndReceiveText("/", HTTP_1_1, body);
        assertThat(rsp.version()).isEqualTo("HTTP/1.1");
        assertThat(rsp.statusCode()).isEqualTo(200);
        assertThat(rsp.body()).isEqualTo(body);
    }
    
    private static Stream<String> smallBodies() {
        return requestBodies(REQUESTS_PER_BATCH, 0, 10);
    }
    
    private static Stream<String> bigBodies() {
        final int channelBufferPoolSize = 5 * 16 * 1_024,
                  halfOfIt = channelBufferPoolSize / 2,
                  enlarged = channelBufferPoolSize * 10;
        return requestBodies(REQUESTS_PER_BATCH, halfOfIt, enlarged);
    }
    
    private Stream<Arguments> smallBodiesAndClient() {
        return augmentWithClientExceptFor(TwoHundredRequestsFromSameClientTest::smallBodies,
                // smallBodies() may return a 0-length body, and Reactor (surprise!)
                // will then return a null response causing NPE.
                // TODO: Either remove this phenomenally shitty client altogether or hack
                //       the implementation just as we had to hack HttpClientFacade.getEmpty().
                REACTOR);
    }
    
    private Stream<Arguments> bigBodiesAndClient() {
        return augmentWithClientExceptFor(TwoHundredRequestsFromSameClientTest::bigBodies,
                // Apache will for some reason switch to chunked encoding. Not
                // compressed, either. So not sure what the hell they are up to.
                // Currently, the server does not decode chunked. The effect was
                // a returned empty body failing the assert (the Apache request
                // likely had no content length).
                // 
                // Reactor adds chunked for all requests, and so, was temporarily
                // hacked - no other alternative. Apache works for at least small
                // requests, and, there's apparently no way to disable chunked,
                // which I wouldn't want to do anyways as such a hack would be
                // specific to one test only.
                // 
                // TODO: Whenever we have chunked decoding, stop excluding Apache.
                APACHE);
    }
    
    private Stream<Arguments> augmentWithClientExceptFor(
            Supplier<Stream<String>> batch, HttpClientFacade.Implementation faulty)
    {
        return HttpClientFacade.Implementation.createAllExceptFor(serverPort(), faulty)
                .flatMap(client -> batch.get()
                        .map(body -> Arguments.of(body, client)));
    }
    
    /**
     * Builds a stream that generates request bodies of various lengths.<p>
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
            return DataUtil.text(r.nextInt(minLen, maxLen + 1));
        };
        return Stream.generate(s).limit(n);
    }
}