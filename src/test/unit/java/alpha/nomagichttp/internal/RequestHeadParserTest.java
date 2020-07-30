package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.RequestHeadParseException;
import alpha.nomagichttp.test.Logging;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;

import static java.lang.Integer.MAX_VALUE;
import static java.lang.System.Logger.Level.ALL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class RequestHeadParserTest
{
    private static final TestServer SERVER = new TestServer();
    
    private SocketChannelOperations client;
    private CompletionStage<RequestHead> testee;
    
    @BeforeAll
    static void beforeAll() throws IOException {
        Logging.setLevel(RequestHeadParser.class, ALL);
        SERVER.start();
    }
    
    @BeforeEach
    void beforeEach() throws Throwable {
        client = new SocketChannelOperations(SERVER.newClient());
        Flow.Publisher<? extends PooledByteBufferHolder> child = new ChannelBytePublisher(SERVER.accept());
        testee = new RequestHeadParser(child, MAX_VALUE).asCompletionStage();
    }
    
    @AfterEach
    void afterEach() throws IOException {
        if (client != null) {
            client.close();
        }
    }
    
    @AfterAll
    static void afterAll() throws IOException {
        SERVER.close();
    }
    
    @Test
    void happypath_headers_yes() throws Throwable {
        String request =
            "GET /hello.txt HTTP/1.1\n" +
            "User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3\r\n" + // <-- CR ignored
            "Host: www.example.com\n" +
            "Accept: text/plain;charset=utf-8\n\r\n";  // <-- CR ignored
        
        client.write(request);
        
        assertHead().containsExactly(
            "GET", "/hello.txt", "HTTP/1.1", headers(
            "User-Agent", "curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3",
            "Host", "www.example.com",
            "Accept", "text/plain;charset=utf-8"));
    }
    
    @Test
    void happypath_headers_no() throws Throwable {
        // any whitespace between tokens (except for HTTP version) is a delimiter
        String request = "GET\t/hello.txt\tHTTP/1.1\r\n\r\n";
        client.write(request);
        assertHead().containsExactly("GET", "/hello.txt", "HTTP/1.1", headers());
    }
    
    // METHOD
    // ------
    
    @Test
    void method_leading_whitespace_ignored() throws Throwable {
        String request = "\r\n \t \r\n GET /hello.txt HTTP/1.1\r\n\n";
        client.write(request);
        assertHead().containsExactly("GET", "/hello.txt", "HTTP/1.1", headers());
    }
    
    // REQUEST-TARGET
    // --------------
    
    @Test
    void requesttarget_leading_whitespace_ignored() throws Throwable {
        String request = "GET\r \t/hello.txt HTTP/1.1\n\n";
        client.write(request);
        assertHead().containsExactly("GET", "/hello.txt", "HTTP/1.1", headers());
    }
    
    @Test
    void requesttarget_leading_whitespace_linefeed_illegal() throws Throwable {
        String p1 = "GET ", p2 = "\n/hel....";
        client.write(p1 + p2);
        waitForCompletion();
        
        assertThat(testee).hasFailedWithThrowableThat()
                .isInstanceOf(RequestHeadParseException.class)
                .hasMessage("Unexpected char.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    // HTTP Version
    // ------------
    
    @Test
    void httpversion_leading_whitespace_ignored() throws Throwable {
        String request = "GET /hello.txt \tHTTP/1.1\n\n";
        client.write(request);
        assertHead().containsExactly("GET", "/hello.txt", "HTTP/1.1", headers());
    }
    
    @Test
    void httpversion_leading_whitespace_linefeed_illegal() throws Throwable {
        String p1 = "GET /hello.txt ", p2 = "\nHTTP....";
        client.write(p1 + p2);
        waitForCompletion();
        
        assertThat(testee).hasFailedWithThrowableThat()
                .isInstanceOf(RequestHeadParseException.class)
                .hasMessage("Empty HTTP-version.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    @Test
    void httpversion_illegal_linebreak() throws Throwable {
        // CR serves as a delimiter ("any whitespace") between method and
        // request-target. But for the HTTP version token, which is waiting on
        // a newline to be his delimiter, then it is required that if CR is
        // provided, it must be followed by LF.
        // TODO: That's pretty inconsistent, giving CR different semantics. Needs research.
        
        String p1 = "GET\r/hello.txt\r", p2 = "Boom!";
        client.write(p1 + p2);
        waitForCompletion();
        
        assertThat(testee).hasFailedWithThrowableThat()
                .isInstanceOf(RequestHeadParseException.class)
                .hasMessage("CR followed by something other than LF.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    @Test
    void httpversion_illegal_whitespace_in_token() throws Throwable {
        String p1 = "GET /hello.txt HT", p2 = " TP/1....";
        client.write(p1 + p2);
        waitForCompletion();
        
        assertThat(testee).hasFailedWithThrowableThat()
                .isInstanceOf(RequestHeadParseException.class)
                .hasMessage("Whitespace in HTTP-version not accepted.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    // HEADER KEY
    // ----------
    
    @Test
    void header_key_space_name_1a() throws Throwable {
        String p1 =
            "A B C\n" +
            "Has", p2 = " Space: blabla\n\n"; // <-- space added in key/name
        
        client.write(p1 + p2);
        waitForCompletion();
        
        assertThat(testee).hasFailedWithThrowableThat()
                .isInstanceOf(RequestHeadParseException.class)
                .hasMessage("Whitespace in header key or before colon is not accepted.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    @Test
    void header_key_space_name_1b() throws Throwable {
        String p1 =
            "A B C\n" +
            "Has", p2 = "\nSpace: blabla\n\n"; // <-- space as LF
        
        client.write(p1 + p2);
        waitForCompletion();
        
        assertThat(testee).hasFailedWithThrowableThat()
                .isInstanceOf(RequestHeadParseException.class)
                .hasMessage("Whitespace in header key or before colon is not accepted.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    @Test
    void header_key_space_name_2() throws Throwable {
        String p1 =
            "A B C\n" +
            "Has-Space", p2 = " : blabla\n\n"; // <-- space added before colon
        
        client.write(p1 + p2);
        waitForCompletion();
        
        assertThat(testee).hasFailedWithThrowableThat()
                .isInstanceOf(RequestHeadParseException.class)
                .hasMessage("Whitespace in header key or before colon is not accepted.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    @Test
    void header_key_space_name_3() throws Throwable {
        String p1 =
            "A B C\n", p2 =
            " Has-Space: blabla\n\n"; // <-- space added before key/name
        
        client.write(p1 + p2);
        waitForCompletion();
        
        assertThat(testee).hasFailedWithThrowableThat()
                .isInstanceOf(RequestHeadParseException.class)
                .hasMessage("Leading whitespace in header key not accepted.")
                .extracting("pos").isEqualTo(p1.length());
    }
    
    // HEADER VALUE
    // ------------
    
    @Test
    void header_value_folded_normal() throws Throwable {
        String request =
            "A B C\n" +
            "Key: Line 1\n" +
            "  Line 2\n" +
            "Another: Value\n\n";
        
        client.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "Key", "Line 1 Line 2",
            "Another", "Value"));
    }
    
    // Empty keys are valid, but here "Line 1" is considered a folded value.
    @Test
    void header_value_folded_breakImmediately() throws Throwable {
        String request =
            "A B C\n" +
            "Key:   \n" +
            " Line 1   \n" + 
            "   Line 2   \n\n";
        
        client.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "Key", "Line 1   Line 2")); // <-- trailing space on line 1 kept as value
    }
    
    @Test
    void header_value_folded_we_thought_but_head_ended_instead() throws Throwable {
        String request =
            "A B C\n" +
            "Key:\n" +
            "   \n\n"; // <-- parser first believes this is a continuation of last header value
        
        client.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "Key", ""));
    }
    
    @Test
    void header_value_empty_singleton_1() throws Throwable {
        String request =
            "A B C\n" +
            "Key:\n\n";
        
        client.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "Key", ""));
    }
    
    @Test
    void header_value_empty_singleton_2() throws Throwable {
        String request =
            "A B C\n" +
            "Key:   \n\n"; // <-- trailing whitespace..
        
        client.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "Key", "")); // <-- ..is still stripped
    }
    
    @Test
    void header__value_empty_enclosed() throws Throwable {
        String request =
            "A B C\n" +
            "First: Has value\n" +
            "Second:\n" + 
            "Third: Also has value.\n" +
            "Second:\n\n";
        
        client.write(request);
        
        assertHead().containsExactly(
            "A", "B", "C", headers(
            "First", "Has value",
            "Second", "",
            "Third", "Also has value.",
            "Second", ""));
    }
    
    private AbstractListAssert<?, List<?>, Object, ObjectAssert<Object>> assertHead()
            throws InterruptedException, ExecutionException, TimeoutException
    {
        return assertThat(actual()).extracting(
                RequestHead::method, RequestHead::requestTarget, RequestHead::httpVersion, RequestHead::headers);
    }
    
    private RequestHead actual() throws InterruptedException, ExecutionException, TimeoutException {
        return testee.toCompletableFuture().get(2, SECONDS);
    }
    
    private void waitForCompletion() throws TimeoutException, InterruptedException {
        try {
            actual();
        }
        catch (ExecutionException e) {
            // Ignore
        }
    }
    
    private static HttpHeaders headers(String... keyValuePairs) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        
        for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
            String k = keyValuePairs[i],
                   v = keyValuePairs[i + 1];
            
            map.computeIfAbsent(k, k0 -> new ArrayList<>(1)).add(v);
        }
        
        return HttpHeaders.of(map, (k, v) -> true);
    }
}