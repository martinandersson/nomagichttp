package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.message.RequestHeadParseException;
import alpha.nomagichttp.testutil.Logging;
import alpha.nomagichttp.testutil.SkeletonServer;
import alpha.nomagichttp.testutil.TestClient;
import alpha.nomagichttp.util.Headers;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.Config.configuration;
import static alpha.nomagichttp.testutil.Assertions.assertFails;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.System.Logger.Level.ALL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class RequestHeadSubscriberTest
{
    private static SkeletonServer SERVER;
    private static TestClient CLIENT;
    private CompletionStage<RequestHead> testee;
    
    @BeforeAll
    static void beforeAll() throws IOException {
        Logging.setLevel(RequestHeadSubscriber.class, ALL);
        SERVER = new SkeletonServer();
        SERVER.start();
        CLIENT = new TestClient(SERVER::newConnection);
    }
    
    @AfterAll
    static void afterAll() throws IOException {
        SERVER.close();
    }
    
    CompletionStage<RequestHead> testee() throws InterruptedException {
        if (testee == null) {
            HttpServer fake = HttpServer.create(configuration()
                    .maxRequestHeadSize(MAX_VALUE).build());
            DefaultClientChannel chan = new DefaultClientChannel(SERVER.accept(), fake);
            Flow.Publisher<DefaultPooledByteBufferHolder> bytes = new ChannelByteBufferPublisher(chan);
            RequestHeadSubscriber rhp = new RequestHeadSubscriber(fake);
            bytes.subscribe(rhp);
            testee = rhp.asCompletionStage();
        }
        return testee;
    }
    
    @Test
    void happypath_headers_yes() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        String request =
            "GET /hello.txt HTTP/1.1\n" +
            "User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3\r\n" + // <-- CR ignored
            "Host: www.example.com\n" +
            "Accept: text/plain;charset=utf-8\n\r\n";  // <-- CR ignored
        
        CLIENT.write(request);
        
        assertHead().containsExactly(
            "GET", "/hello.txt", "HTTP/1.1", headers(
            "User-Agent", "curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3",
            "Host", "www.example.com",
            "Accept", "text/plain;charset=utf-8"));
    }
    
    @Test
    void happypath_headers_no() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        // any whitespace between tokens (except for HTTP version) is a delimiter
        CLIENT.write("GET\t/hello.txt\tHTTP/1.1\r\n\r\n");
        assertHead().containsExactly("GET", "/hello.txt", "HTTP/1.1", headers());
    }
    
    // METHOD
    // ------
    
    @Test
    void method_leading_whitespace_ignored() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        CLIENT.write("\r\n \t \r\n GET /hello.txt HTTP/1.1\r\n\n");
        assertHead().containsExactly("GET", "/hello.txt", "HTTP/1.1", headers());
    }
    
    // REQUEST-TARGET
    // --------------
    
    @Test
    void requesttarget_leading_whitespace_ignored() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        String request = "GET\r \t/hello.txt HTTP/1.1\n\n";
        CLIENT.write(request);
        assertHead().containsExactly("GET", "/hello.txt", "HTTP/1.1", headers());
    }
    
    @Test
    void requesttarget_leading_whitespace_linefeed_illegal() throws
            IOException, TimeoutException, InterruptedException
    {
        String p1 = "GET ", p2 = "\n/hel....";
        CLIENT.write(p1 + p2);
        
        assertFails(testee())
                .isExactlyInstanceOf(RequestHeadParseException.class)
                .hasMessage("Unexpected char.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    // HTTP Version
    // ------------
    
    @Test
    void httpversion_leading_whitespace_ignored() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        String request = "GET /hello.txt \tHTTP/1.1\n\n";
        CLIENT.write(request);
        assertHead().containsExactly("GET", "/hello.txt", "HTTP/1.1", headers());
    }
    
    @Test
    void httpversion_leading_whitespace_linefeed_illegal()
            throws IOException, InterruptedException, TimeoutException {
        String p1 = "GET /hello.txt ", p2 = "\nHTTP....";
        CLIENT.write(p1 + p2);
        
        assertFails(testee())
                .isExactlyInstanceOf(RequestHeadParseException.class)
                .hasMessage("Empty HTTP-version.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    @Test
    void httpversion_illegal_linebreak() throws IOException, InterruptedException, TimeoutException {
        // CR serves as a delimiter ("any whitespace") between method and
        // request-target. But for the HTTP version token, which is waiting on
        // a newline to be his delimiter, then it is required that if CR is
        // provided, it must be followed by LF.
        // TODO: That's pretty inconsistent, giving CR different semantics. Needs research.
        
        String p1 = "GET\r/hello.txt\r", p2 = "Boom!";
        CLIENT.write(p1 + p2);
        
        assertFails(testee())
                .isExactlyInstanceOf(RequestHeadParseException.class)
                .hasMessage("CR followed by something other than LF.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    @Test
    void httpversion_illegal_whitespace_in_token() throws IOException, InterruptedException, TimeoutException {
        String p1 = "GET /hello.txt HT", p2 = " TP/1....";
        CLIENT.write(p1 + p2);
        
        assertFails(testee())
                .isExactlyInstanceOf(RequestHeadParseException.class)
                .hasMessage("Whitespace in HTTP-version not accepted.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    // HEADER KEY
    // ----------
    
    @Test
    void header_key_space_name_1a() throws IOException, InterruptedException, TimeoutException {
        String p1 =
            "A B C\n" +
            "Has", p2 = " Space: blabla\n\n"; // <-- space added in key/name
        
        CLIENT.write(p1 + p2);
        
        assertFails(testee())
                .isExactlyInstanceOf(RequestHeadParseException.class)
                .hasMessage("Whitespace in header key or before colon is not accepted.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    @Test
    void header_key_space_name_1b() throws IOException, InterruptedException, TimeoutException {
        String p1 =
            "A B C\n" +
            "Has", p2 = "\nSpace: blabla\n\n"; // <-- space as LF
        
        CLIENT.write(p1 + p2);
        
        assertFails(testee())
                .isExactlyInstanceOf(RequestHeadParseException.class)
                .hasMessage("Whitespace in header key or before colon is not accepted.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    @Test
    void header_key_space_name_2() throws IOException, InterruptedException, TimeoutException {
        String p1 =
            "A B C\n" +
            "Has-Space", p2 = " : blabla\n\n"; // <-- space added before colon
        
        CLIENT.write(p1 + p2);
        
        assertFails(testee())
                .isExactlyInstanceOf(RequestHeadParseException.class)
                .hasMessage("Whitespace in header key or before colon is not accepted.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    @Test
    void header_key_space_name_3() throws IOException, InterruptedException, TimeoutException {
        String p1 =
            "A B C\n", p2 =
            " Has-Space: blabla\n\n"; // <-- space added before key/name
        
        CLIENT.write(p1 + p2);
        
        assertFails(testee())
                .isExactlyInstanceOf(RequestHeadParseException.class)
                .hasMessage("Leading whitespace in header key not accepted.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    // HEADER VALUE
    // ------------
    
    @Test
    void header_value_folded_normal() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        String request =
            "A B C\n" +
            "Key: Line 1\n" +
            "  Line 2\n" +
            "Another: Value\n\n";
        
        CLIENT.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "Key", "Line 1 Line 2",
            "Another", "Value"));
    }
    
    // Empty keys are valid, but here "Line 1" is considered a folded value.
    @Test
    void header_value_folded_breakImmediately() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        String request =
            "A B C\n" +
            "Key:   \n" +
            " Line 1   \n" + 
            "   Line 2   \n\n";
        
        CLIENT.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "Key", "Line 1   Line 2")); // <-- trailing space on line 1 kept as value
    }
    
    @Test
    void header_value_folded_we_thought_but_head_ended_instead() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        String request =
            "A B C\n" +
            "Key:\n" +
            "   \n\n"; // <-- parser first believes this is a continuation of last header value
        
        CLIENT.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "Key", ""));
    }
    
    @Test
    void header_value_empty_singleton_1() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        String request =
            "A B C\n" +
            "Key:\n\n";
        
        CLIENT.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "Key", ""));
    }
    
    @Test
    void header_value_empty_singleton_2() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        String request =
            "A B C\n" +
            "Key:   \n\n"; // <-- trailing whitespace..
        
        CLIENT.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "Key", "")); // <-- ..is still stripped
    }
    
    @Test
    void header__value_empty_enclosed() throws
            IOException, InterruptedException, ExecutionException, TimeoutException
    {
        String request =
            "A B C\n" +
            "First: Has value\n" +
            "Second:\n" + 
            "Third: Also has value.\n" +
            "Second:\n\n";
        
        CLIENT.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "First", "Has value",
            "Second", "",
            "Third", "Also has value.",
            "Second", ""));
    }
    
    private AbstractListAssert<?, List<?>, Object, ObjectAssert<Object>>
            assertHead() throws InterruptedException, ExecutionException, TimeoutException
    {
        var head = testee().toCompletableFuture().get(3, SECONDS);
        return assertThat(head).extracting(
                RequestHead::method,
                RequestHead::requestTarget,
                RequestHead::httpVersion,
                RequestHead::headers);
    }
    
    private static HttpHeaders headers(String... keyValuePairs) {
        return Headers.of(keyValuePairs);
    }
}