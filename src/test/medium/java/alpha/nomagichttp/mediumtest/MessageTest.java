package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.testutil.AbstractRealTest;
import alpha.nomagichttp.testutil.HttpClientFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.Assertions.assertHeaders;
import static alpha.nomagichttp.testutil.Headers.linkedHashMap;
import static alpha.nomagichttp.testutil.HttpClientFacade.Implementation.JDK;
import static alpha.nomagichttp.testutil.HttpClientFacade.Implementation.JETTY;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestFiles.writeTempFile;
import static alpha.nomagichttp.testutil.TestRequestHandlers.respondIsBodyEmpty;
import static alpha.nomagichttp.testutil.TestRequests.get;
import static alpha.nomagichttp.testutil.TestRequests.post;
import static alpha.nomagichttp.util.ByteBufferIterables.ofFile;
import static alpha.nomagichttp.util.ByteBufferIterables.ofSupplier;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.List.of;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coarse-grained HTTP exchanges suitable to run using different clients.<p>
 * 
 * Tests here perform classical "GET ..." requests and then expect "HTTP/1.1
 * 200 ..." responses. The purpose is to ensure the NoMagicHTTP library can in
 * practice be used by different HTTP clients.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class MessageTest extends AbstractRealTest
{
    // TODO: Run tests using different clients
    
    // TODO: If this can't run using different clients, just do GET instead of POST
    @Test
    void request_body_empty() throws IOException {
        server().add("/",
            respondIsBodyEmpty());
        String res = client().writeReadTextUntil(post(""), "true");
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4"                       + CRLF + CRLF +
            
            "true");
    }
    
    // TODO: Any client that can't do HTTP/1.0 can simply be ignored
    /**
     * Can make an HTTP/1.0 request (gets HTTP/1.1 response).<p>
     * 
     * See {@link ErrorTest} for cases related to unsupported versions.
     */
    @Test
    void http_1_0() throws IOException {
        server().add("/", GET().apply(req ->
            text("Received " + req.httpVersion())));
        
        String resp = client().writeReadTextUntil(
            "GET / HTTP/1.0" + CRLF + CRLF, "Received HTTP/1.0");
        
        assertThat(resp).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Connection: close"                       + CRLF +
            "Content-Length: 17"                      + CRLF + CRLF +
            
            "Received HTTP/1.0");
    }
    
    // TODO: Lots of so called HTTP clients will likely not be able to receive
    //       multiple responses, just ignore them.
    // Note: There's a least one other expect100Continue-test in DetailTest
    @Test
    void expect100Continue_onFirstBodyAccess() throws IOException {
        server().add("/", POST().apply(req ->
            text(req.body().toText())));
        
        String req = "POST / HTTP/1.1" + CRLF +
            "Expect: 100-continue"     + CRLF +
            "Content-Length: 2"        + CRLF +
            "Content-Type: text/plain" + CRLF + CRLF +
            
            "Hi";
        
        String rsp = client().writeReadTextUntil(req, "Hi");
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 100 Continue"                   + CRLF + CRLF +
            
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 2"                       + CRLF + CRLF +
            
            "Hi");
    }
    
    @Test
    @DisplayName("http_1_1_chunked_request/TestClient")
    void http_1_1_chunked_request() throws IOException {
        addRouteThatEchoesTheRequestBody();
        var rsp = client().writeReadTextUntilEOS("""
                POST / HTTP/1.1
                Transfer-Encoding: chunked
                
                5
                Hello
                6
                World!
                0
                
                """);
        // Both chunks fit into one buffer processed by ChunkedDecoder
        assertThat(rsp).isEqualTo("""
                HTTP/1.1 200 OK\r
                Content-Type: application/octet-stream\r
                Connection: close\r
                Transfer-Encoding: chunked\r
                \r
                0000000b\r
                HelloWorld!\r
                0\r\n\r\n""");
    }
    
    @ParameterizedTest(name = "http_1_1_chunked_request_compatibility/{0}")
    @EnumSource
    void http_1_1_chunked_request_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, InterruptedException, ExecutionException, TimeoutException
    {
        addRouteThatEchoesTheRequestBody();
        var ch1 = "Hello".getBytes(US_ASCII);
        var ch2 = "World!".getBytes(US_ASCII);
        var cli = impl.create(serverPort());
        var rsp = cli.postChunksAndReceiveText("/", ch1, ch2);
        assertThat(rsp.statusCode()).isEqualTo(200);
        assertThat(rsp.headers().firstValue("Transfer-Encoding")).hasValue("chunked");
        assertThat(rsp.body()).isEqualTo("HelloWorld!");
    }
    
    private void addRouteThatEchoesTheRequestBody() throws IOException {
        server().add("/", POST().apply(req -> {
            assertThat(req.headers().transferEncoding().getLast())
                    .isEqualTo("chunked");
            return ok(ofSupplier(req.body().iterator()::next))
                    .toBuilder()
                    .header("Connection", "close")
                    .build();
        }));
    }
    
    @Test
    @DisplayName("http_1_1_chunked_response/TestClient")
    void http_1_1_chunked_response() throws IOException {
        addRouteThatRespondChunked();
        var rsp = client().writeReadTextUntilEOS(
            get());
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 200 OK\r
            Content-Type: text/plain; charset=utf-8\r
            Connection: close\r
            Trailer: One, Two\r
            Transfer-Encoding: chunked\r
            \r
            00000005\r
            Hello\r
            0\r
            One: Foo\r
            Two: Bar\r
            \r
            """);
    }
    
    @ParameterizedTest(name = "http_1_1_chunked_response_compatibility/{0}")
    @EnumSource
    void http_1_1_chunked_response_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        // JDK can't even decode the body if it has trailers, throws AssertionError lol
        // TODO: Try again with later version
        assumeTrue(impl != JDK);
        addRouteThatRespondChunked();
        var cli = impl.create(serverPort());
        var rsp = cli.getText("/", HTTP_1_1);
        assertThat(rsp.statusCode()).isEqualTo(200);
        assertThat(rsp.reasonPhrase()).isEqualTo("OK");
        assertThat(rsp.body()).isEqualTo("Hello");
        // Jetty has no public support for trailers
        // TODO: Verify Jetty if and when they add support
        if (impl != JETTY) {
            assertHeaders(rsp.trailers()).containsExactly(
                entry("One", of("Foo")), entry("Two", of("Bar")));
        }
    }
    
    private void addRouteThatRespondChunked() throws IOException {
        server().add("/", GET().apply(req ->
                text("Hello")
                    .toBuilder()
                    .addHeaders(
                        "Connection", "close",
                        "Trailer", "One, Two")
                    .addTrailers(() -> linkedHashMap(
                        "One", "Foo",
                        "Two", "Bar"))
                    .build()));
    }
    
    @Test
    void fileResponse_okay() throws IOException, InterruptedException {
        var file = writeTempFile(asciiBytes("Hello, World!"));;
        server().add(
            "/", GET().apply(req -> ok(ofFile(file))));
        var rsp = client().writeReadTextUntil(get(), "!");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 200 OK\r
            Content-Type: application/octet-stream\r
            Content-Length: 13\r
            \r
            Hello, World!""");
        assertThatNoWarningOrErrorIsLogged();
    }
}