package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.DecoderException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import alpha.nomagichttp.testutil.functional.HttpClientFacade;
import alpha.nomagichttp.util.ByteBufferIterables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.Assertions.assertHeaders;
import static alpha.nomagichttp.testutil.Headers.linkedHashMap;
import static alpha.nomagichttp.testutil.functional.Constants.OTHER;
import static alpha.nomagichttp.testutil.functional.Constants.TEST_CLIENT;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.JDK;
import static alpha.nomagichttp.util.ByteBufferIterables.ofSupplier;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.List.of;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

///  Tests of "Transfer-Encoding: chunked".
final class ChunkedCodingTest extends AbstractRealTest
{
    @Nested
    class CompatibilityRequest {
        @Test
        @DisplayName(TEST_CLIENT)
        void testClient() throws IOException {
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
        
        @ParameterizedTest(name = OTHER)
        @EnumSource
        void other(HttpClientFacade.Implementation impl)
                throws IOException, InterruptedException,
                ExecutionException, TimeoutException
        {
            addRouteThatEchoesTheRequestBody();
            var ch1 = "Hello".getBytes(US_ASCII);
            var ch2 = "World!".getBytes(US_ASCII);
            var cli = impl.create(serverPort());
            var rsp = cli.postChunksAndReceiveText("/", ch1, ch2);
            assertThat(rsp.statusCode())
                .isEqualTo(200);
            assertThat(rsp.headers().firstValue("Transfer-Encoding"))
                .hasValue("chunked");
            assertThat(rsp.body())
                .isEqualTo("HelloWorld!");
        }
        
        private void addRouteThatEchoesTheRequestBody() throws IOException {
            server().add("/", POST().apply(req -> {
                assertThat(req.headers().transferEncoding().getLast())
                    .isEqualTo("chunked");
                return ok(ofSupplier(req.body().iterator()::next));
            }));
        }
    }
    
    @Nested
    class CompatibilityResponse {
        @Test
        @DisplayName(TEST_CLIENT)
        void testClient() throws IOException {
            addRouteThatRespondChunked();
            var rsp = client().writeReadTextUntilEOS(
               "GET / HTTP/1.1\n\n");
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
        
        @ParameterizedTest(name = OTHER)
        @EnumSource
        void other(HttpClientFacade.Implementation impl)
                throws IOException, ExecutionException,
                       InterruptedException, TimeoutException
        {
            // JDK can't decode the body if it has trailers (AssertionError)
            // TODO: Try again with later version
            assumeTrue(impl != JDK);
            addRouteThatRespondChunked();
            var cli = impl.create(serverPort());
            var rsp = cli.getText("/", HTTP_1_1);
            assertThat(rsp.statusCode()).isEqualTo(200);
            assertThat(rsp.reasonPhrase()).isEqualTo("OK");
            assertThat(rsp.body()).isEqualTo("Hello");
                assertHeaders(rsp.trailers()).containsExactly(
                    entry("One", of("Foo")), entry("Two", of("Bar")));
        }
        
        private void addRouteThatRespondChunked() throws IOException {
            server().add("/", GET().apply(_ ->
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
    }
    
    @Nested
    class DecoderExc {
        @Test
        void handledByBase() throws IOException {
            server().add("/",
                GET().apply(req -> {
                    req.body().toText();
                    throw new AssertionError();
                }));
            String rsp = client().writeReadTextUntilEOS("""
                GET / HTTP/1.1
                Transfer-Encoding: chunked
                
                ABCDEX.....\n""");
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 400 Bad Request\r
                Connection: close\r
                Content-Length: 0\r\n\r\n""");
            assertThat(pollServerExceptionNow())
                .isExactlyInstanceOf(DecoderException.class)
                .hasNoSuppressedExceptions()
                .hasMessage("""
                    java.lang.NumberFormatException: \
                    not a hexadecimal digit: "X" = 88""");
        }
        
        @Test
        void handledByApp() throws IOException {
            // Must kick off the subscription to provoke the exception
            server().add("/",
                GET().apply(req -> {
                    try {
                        req.body().toText();
                    } catch (DecoderException e) {
                        return text(e.toString());
                    }
                    throw new AssertionError();
                }));
            String rsp = client().writeReadTextUntilEOS("""
                GET / HTTP/1.1
                Transfer-Encoding: chunked
                Connection: close
                
                ABCDEX.....\n""");
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain; charset=utf-8\r
                Connection: close\r
                Content-Length: 110\r
                \r
                alpha.nomagichttp.message.DecoderException: \
                java.lang.NumberFormatException: \
                not a hexadecimal digit: "X" = 88""");
        }
    }
    
    @Nested
    class ResponseBodyUnknownLength {
        @Test
        void hasContent() throws IOException {
            var empty = ByteBuffer.allocate(0);
            var items = List.of(asciiBytes("World"), empty);
            var body = ByteBufferIterables.ofSupplier(items.iterator()::next);
            server().add("/", GET().apply(_ ->
                ok(body)));
            String rsp = client().writeReadTextUntil(
                "GET / HTTP/1.1\n\n", "0\r\n\r\n");
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 200 OK\r
                Content-Type: application/octet-stream\r
                Transfer-Encoding: chunked\r
                \r
                00000005\r
                World\r
                0\r\n\r
                """);
        }
        
        @Test
        void noContent() throws IOException {
            // TODO: A variant without client setting "Connection: close"
            var empty = new ByteBufferIterable() {
                public ByteBufferIterator iterator() {
                    return ByteBufferIterator.Empty.INSTANCE;
                }
                public long length() {
                    return -1;
                }
            };
            server().add("/", GET().apply(_ ->
                ok(empty)));
            String rsp = client().writeReadTextUntilEOS("""
                GET / HTTP/1.1
                Connection: close
                
                """);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 200 OK\r
                Content-Type: application/octet-stream\r
                Connection: close\r
                Transfer-Encoding: chunked\r
                \r
                0\r\n\r
                """);
        }
        
        // And what about testing a request body of unknown length?
        // The request must specify Content-Length or Transfer-Encoding.
        // Only the server's response may have unknown length terminated by
        // connection close (RFC 9112 §6.3, bullet item 7 & 8).
    }
}
