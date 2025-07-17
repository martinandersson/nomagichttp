package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.IdleConnectionException;
import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.DecoderException;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.MaxRequestBodyBufferSizeException;
import alpha.nomagichttp.message.MaxRequestHeadSizeException;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestLineParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.UnsupportedTransferCodingException;
import alpha.nomagichttp.route.AmbiguousHandlerException;
import alpha.nomagichttp.route.MediaTypeNotAcceptedException;
import alpha.nomagichttp.route.MediaTypeUnsupportedException;
import alpha.nomagichttp.route.MethodNotAllowedException;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import alpha.nomagichttp.util.Throwing;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.Serial;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.get;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.post;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.HEAD;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.handler.RequestHandler.TRACE;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.status;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.functional.Environment.isGitHubActions;
import static alpha.nomagichttp.testutil.functional.Environment.isJitPack;
import static alpha.nomagichttp.util.ByteBufferIterables.ofString;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests concerning server errors.<p>
 * 
 * Tests in this class usually provoke exceptions, run asserts on exceptions
 * delivered to the exception handler, and of course, assert the response.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ErrorTest extends AbstractRealTest
{
    private static final System.Logger LOG
            = System.getLogger(ErrorTest.class.getPackageName());
    
    private static final
        Throwing.Function<Request, Response, Exception> NOP = _ -> null;
    
    private static final class OopsException extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
        OopsException() { }
        OopsException(String msg) { super(msg); }
    }
    
    @Test
    void AmbiguousHandlerExc() throws IOException, InterruptedException {
        server().add("/",
            GET().produces("text/plain").apply(NOP),
            GET().produces("text/html").apply(NOP));
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1\n\n");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 500 Internal Server Error\r
            Content-Length: 0\r\n\r\n""");
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(AmbiguousHandlerException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                Ambiguous: [\
                DefaultRequestHandler{method="GET", \
                consumes="<nothing and all>", produces="text/plain", logic=?}, \
                DefaultRequestHandler{method="GET", \
                consumes="<nothing and all>", produces="text/html", logic=?}]""");
    }
    
    @Test
    void BadHeaderExc() throws IOException, InterruptedException {
        server().add("/",
            GET().apply(NOP));
        String rsp = client().writeReadTextUntilEOS("""
            GET / HTTP/1.1\r
            Content-Type: BOOM!\r\n\r
            """);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 400 Bad Request\r
            Connection: close\r
            Content-Length: 0\r\n\r\n""");
        assertThat(pollServerException())
            .isExactlyInstanceOf(BadHeaderException.class)
            .hasMessage("Failed to parse Content-Type header.")
            .hasNoSuppressedExceptions()
            .cause()
                .isExactlyInstanceOf(MediaTypeParseException.class)
                .hasNoSuppressedExceptions()
                .hasNoCause()
                .hasMessage("""
                    Can not parse "BOOM!". \
                    Expected exactly one forward slash in <type/subtype>.""");
    }
    
    @Test
    void BadRequestExc() throws IOException, InterruptedException {
        server();
        String rsp = client().writeReadTextUntilEOS("""
            GET / HTTP/1.1\r
            Transfer-Encoding: chunked\r
            Content-Length: 123\r\n\r
            """);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 400 Bad Request\r
            Connection: close\r
            Content-Length: 0\r\n\r\n""");
        assertThat(pollServerException())
            .isExactlyInstanceOf(BadRequestException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Content-Length and Transfer-Encoding are both present.");
    }
    
    @Nested
    class DecoderExc {
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
        
        @Test
        void handledByExceptionHandler()
                throws IOException
        {
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
    }
    
    @Test
    void HeaderParseExc() throws IOException, InterruptedException {
        server();
        String rsp = client().writeReadTextUntilEOS("""
             GET / HTTP/1.1\r
             H e a d e r: Oops!\r\n""");
        assertThat(rsp).isEqualTo("""
             HTTP/1.1 400 Bad Request\r
             Connection: close\r
             Content-Length: 0\r\n\r\n""");
        assertThat(pollServerException())
            .isExactlyInstanceOf(HeaderParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                HeaderParseException{\
                prev=(hex:0x48, decimal:72, char:"H"), \
                curr=(hex:0x20, decimal:32, char:" "), pos=17, \
                msg=Whitespace in header name or before colon is not accepted.}""");
    }
    
    @Test
    void HttpVersionParseExc() throws IOException, InterruptedException {
        server();
        String rsp = client().writeReadTextUntilNewlines(
            "GET / Oops"               + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 400 Bad Request" + CRLF +
            "Connection: close"        + CRLF +
            "Content-Length: 0"        + CRLF + CRLF);
        assertThat(pollServerException())
            .isExactlyInstanceOf(HttpVersionParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("No forward slash.");
    }
    
    // Some newer versions are currently not supported
    @ParameterizedTest
    @CsvSource({"2,true", "3,true", "999,false"})
    void HttpVersionTooNewExc(String version, boolean hasLiteral)
            throws IOException, InterruptedException
    {
        server();
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/" + version                   + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 505 HTTP Version Not Supported" + CRLF +
            "Connection: close"                       + CRLF +
            "Content-Length: 0"                       + CRLF + CRLF);
        var throwable = assertThat(pollServerException())
            .isExactlyInstanceOf(HttpVersionTooNewException.class)
            .hasNoSuppressedExceptions();
        if (hasLiteral) {
            throwable.hasMessage(null)
                     .hasNoCause();
        } else {
            throwable.hasMessage("java.lang.IllegalArgumentException: 999:")
                     .hasNoSuppressedExceptions()
                     .cause()
                         .isExactlyInstanceOf(IllegalArgumentException.class)
                         .hasMessage("999:")
                         .hasNoSuppressedExceptions()
                         .hasNoCause();
        }
    }
    
    @Nested
    class HttpVersionTooOldExc {
        // By default, server rejects clients older than HTTP/1.0
        @ParameterizedTest
        @CsvSource({"-1.23,false", "0.5,false", "0.8,false", "0.9,true"})
        void lessThan1_0(String version, boolean hasLiteral)
                throws IOException, InterruptedException
        {
            server();
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/" + version         + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 426 Upgrade Required" + CRLF +
                "Upgrade: HTTP/1.1"             + CRLF +
                "Connection: upgrade, close"    + CRLF +
                "Content-Length: 0"             + CRLF + CRLF);
            var throwable = assertThat(pollServerException())
                .isExactlyInstanceOf(HttpVersionTooOldException.class)
                .hasNoSuppressedExceptions();
            if (hasLiteral) {
                throwable.hasMessage(null)
                         .hasNoSuppressedExceptions()
                         .hasNoCause();
            } else {
                var v = version.replace(".", ":");
                throwable.hasMessage("java.lang.IllegalArgumentException: " + v)
                         .cause()
                         .isExactlyInstanceOf(IllegalArgumentException.class)
                         .hasMessage(v)
                         .hasNoSuppressedExceptions()
                         .hasNoCause();
            }
        }
        
        // Server may be configured to reject old clients
        @Test
        void eq1_0()
                throws IOException, InterruptedException
        {
            usingConfiguration()
                .minHttpVersion(HTTP_1_1);
            server();
            String rsp = client().writeReadTextUntilNewlines(
                "GET /not-found HTTP/1.0"       + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 426 Upgrade Required" + CRLF +
                "Upgrade: HTTP/1.1"             + CRLF +
                "Connection: upgrade, close"    + CRLF +
                "Content-Length: 0"             + CRLF+ CRLF);
            assertThat(pollServerException())
                .isExactlyInstanceOf(HttpVersionTooOldException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage(null);
        }
    }
    
    @Test
    void IllegalRequestBodyExc()
            throws IOException, InterruptedException
    {
        server().add("/",
            TRACE().apply(req -> {
                throw new AssertionError("Not invoked.");
            }));
        String rsp = client().writeReadTextUntilNewlines(
            "TRACE / HTTP/1.1"         + CRLF +
            "Content-Length: 1"        + CRLF + CRLF +
            
            "X");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 400 Bad Request" + CRLF +
            "Connection: close"        + CRLF +
            "Content-Length: 0"        + CRLF + CRLF);
        assertThat(pollServerException())
            .isExactlyInstanceOf(IllegalRequestBodyException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Body in a TRACE request.");
    }
    
    @Nested
    class IllegalResponseBodyExc {
        @Test
        void in1xxResponse() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(req -> Response.builder(123)
                         .body(ofString("Body!"))
                         .build()));
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1"                     + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Presumably a body in a 123 (Unknown) response.");
        }
        
        @Test
        void inResponseToHEAD() throws IOException, InterruptedException {
            server().add("/",
                HEAD().apply(req -> text("Body!")));
            String rsp = client().writeReadTextUntilNewlines(
                "HEAD / HTTP/1.1"                    + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                // No Content-Length (see ResponseProcessor.dealWithHeadRequest)
                "HTTP/1.1 500 Internal Server Error" + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Possibly non-empty body in response to a HEAD request.");
        }
    }
    
    @Test
    void MaxRequestHeadSizeExc()
            throws IOException, InterruptedException
    {
        usingConfiguration()
            .maxRequestHeadSize(1);
        server();
        String rsp = client().writeReadTextUntilNewlines(
            "AB");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 413 Entity Too Large\r
            Connection: close\r
            Content-Length: 0\r\n\r\n""");
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(MaxRequestHeadSizeException.class)
            .hasMessage("Configured max tolerance is 1 bytes.")
            .hasNoCause()
            .hasNoSuppressedExceptions();
    }
    
    @Nested
    class MaxRequestBodySizeExc {
        @Test
        void RequestBody_bytesFast() throws IOException, InterruptedException {
            usingConfiguration()
                .maxRequestBodyBufferSize(1);
            server()
                .add("/", POST().apply(req -> {
                    // This should fail
                    req.body().bytes();
                    return null;
                }));
            String rsp = client().writeReadTextUntilNewlines("""
                POST / HTTP/1.1\r
                Content-Length: 2\r
                \r
                AB
                """);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 413 Entity Too Large\r
                Connection: close\r
                Content-Length: 0\r\n\r\n""");
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(MaxRequestBodyBufferSizeException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Configured max tolerance is 1 bytes.");
            }
    }
    
    @Test
    void MediaTypeNotAcceptedExc() throws IOException, InterruptedException {
        server().add("/",
            GET().produces("text/blabla").apply(NOP));
        String rsp = client().writeReadTextUntilNewlines("""
            GET / HTTP/1.1\r
            Accept: text/different\r\n\r\n""");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 406 Not Acceptable\r
            Content-Length: 0\r\n\r\n""");
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(MediaTypeNotAcceptedException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                No handler found matching \
                "Accept: text/different" header in request.""");
    }
    
    @Test
    void MediaTypeParseExc() throws IOException, InterruptedException {
        server().add("/", GET().apply(req -> {
            MediaType.parse("BOOM!");
            throw new AssertionError();
        }));
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1\n\n");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 500 Internal Server Error\r
            Content-Length: 0\r\n\r\n""");
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(MediaTypeParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                Can not parse "BOOM!". \
                Expected exactly one forward slash in <type/subtype>.""");
    }
    
    @Test
    void MediaTypeUnsupportedExc() throws IOException, InterruptedException {
        server().add("/",
            GET().consumes("text/blabla").apply(NOP));
        String rsp = client().writeReadTextUntilNewlines("""
            GET / HTTP/1.1
            Content-Type: text/different\n\n""");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 415 Unsupported Media Type\r
            Content-Length: 0\r\n\r\n""");
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(MediaTypeUnsupportedException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                No handler found matching \
                "Content-Type: text/different" header in request.""");
    }
    
    @Nested
    class MethodNotAllowedExc {
        // Expect 405 (Method Not Allowed)
        @Test
        void BLABLA() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(req -> internalServerError()),
                POST().apply(req -> internalServerError()));
            String rsp = client().writeReadTextUntilNewlines(
                "BLABLA / HTTP/1.1"               + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 405 Method Not Allowed" + CRLF +
                // Actually, order is not defined, let's see for how long this test pass
                "Allow: POST, GET"                + CRLF +
                "Content-Length: 0"               + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(MethodNotAllowedException.class)
                .hasMessage("No handler found for method token \"BLABLA\".")
                .hasNoCause()
                .hasNoSuppressedExceptions();
        }
        
        // ...but if the method is OPTIONS, the default configuration implements it
        @Test
        void OPTIONS() throws IOException, InterruptedException {
            server().add("/",
                    GET().apply(req -> internalServerError()),
                    POST().apply(req -> internalServerError()));
            String rsp = client().writeReadTextUntilNewlines(
                    "OPTIONS / HTTP/1.1"              + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                    "HTTP/1.1 204 No Content"         + CRLF +
                    "Allow: OPTIONS, POST, GET"       + CRLF + CRLF);
            assertThat(pollServerException())
                    .isExactlyInstanceOf(MethodNotAllowedException.class)
                    .hasMessage("No handler found for method token \"OPTIONS\".");
        }
    }
    
    @Nested
    class NoRouteFoundExc {
        @Test
        void standard()
                throws IOException, InterruptedException
        {
            server();
            String rsp = client().writeReadTextUntilNewlines(
                "GET /404 HTTP/1.1"      + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 404 Not Found" + CRLF +
                "Content-Length: 0"      + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(NoRouteFoundException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("/404");
        }
        
        @Test
        void custom() throws IOException {
            usingExceptionHandler((exc, chain, req) ->
                exc instanceof NoRouteFoundException ?
                        status(499, "Custom Not Found!") :
                        chain.proceed());
            server();
            String rsp = client().writeReadTextUntilNewlines(
                "GET /404 HTTP/1.1"              + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 499 Custom Not Found!" + CRLF +
                "Content-Length: 0"              + CRLF + CRLF);
        }
    }
    
    @Test
    void RequestLineParseExc() throws IOException, InterruptedException {
        server();
        String rsp = client().writeReadTextUntilEOS(
            "GET / H T T P ....");
        assertThat(rsp).isEqualTo("""
             HTTP/1.1 400 Bad Request\r
             Connection: close\r
             Content-Length: 0\r\n\r\n""");
        assertThat(pollServerException())
            .isExactlyInstanceOf(RequestLineParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Whitespace in HTTP-version not accepted.");
    }
    
    @Test
    void UnsupportedTransferCodingExc() throws IOException, InterruptedException {
        server();
        String rsp = client().writeReadTextUntilNewlines("""
            GET / HTTP/1.1\r
            Transfer-Encoding: blabla, chunked\r\n\r
            """);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 501 Not Implemented\r
            Connection: close\r
            Content-Length: 0\r\n\r\n""");
        assertThat(pollServerException())
            .isExactlyInstanceOf(UnsupportedTransferCodingException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Unsupported Transfer-Encoding: blabla");
    }
    
    // TODO: fileResponse_blockedByWriteLock
    // Link in JavaDoc MessageTest fileResponse_okay
    
    @Nested
    class IdleConnectionExc {
        @Test
        void duringHead() throws IOException, InterruptedException {
            // On the author's machine, ofMillis(1) was never any problem.
            // But for GitHub's environment, such a short duration may
            // occasionally also time out the write operation (i.e. background
            // thread closes the write stream, and we get no response or a
            // corrupt one)!
            // TODO: Fix brittle and nondeterministic test
            usingConfiguration().timeoutIdleConnection(
                (isGitHubActions() || isJitPack()) ? ofMillis(10) : ofMillis(1));
            server();
            try (var _ = client().openConnection()) {
                // Never send anything and expect a response
                String rsp = client().readTextUntilNewlines();
                assertThat(rsp).isEqualTo(
                    "HTTP/1.1 408 Request Timeout" + CRLF +
                    "Connection: close"            + CRLF +
                    "Content-Length: 0"            + CRLF + CRLF);
                assertThat(pollServerException())
                    .isExactlyInstanceOf(IdleConnectionException.class)
                    .hasMessage(null)
                    .hasNoCause()
                    .hasNoSuppressedExceptions();
                // Timeout triggered by scheduler
                logRecorder().assertContainsOnlyOnce(
                    DEBUG, "Idle connection; shutting down read stream");
            }
        }
        
        // duringResponse() ?
        // Can't configure a short timeout only for the write operation,
        // so skipping this for now.
    }
    
    @Nested
    class Special {
        @Test
        void exceptionHandlerFails() throws IOException, InterruptedException {
            usingExceptionHandler((thr, ch, req) -> {
                throw new OopsException("second");
            });
            server().add("/", GET().apply(req -> {
                throw new OopsException("first");
            }));
            
            String rsp = client().writeReadTextUntilEOS(
                "GET / HTTP/1.1" + CRLF + CRLF);
            // No response
            assertThat(rsp)
                  .isEmpty();
            // But the exceptions were logged
            logRecorder().assertAwaitRemoveThrown()
                  .isExactlyInstanceOf(OopsException.class)
                  .hasMessage("first")
                  .hasNoCause()
                  .hasSuppressedException(new OopsException("second"));
        }
        
        /**
         * The channel remains fully open.
         * 
         * @see ClientLifeCycleTest.UnexpectedEndFromClient#serverReceivedPartialHead()
         */
        @Test
        void requestBodyConsumerFails() throws IOException, InterruptedException {
            onExceptionAssert(OopsException.class, ch ->
                assertThat(ch.areBothStreamsOpen()).isTrue());
            server().add("/", POST().apply(req -> {
                // Read one byte before crash
                req.body().iterator().next().get();
                throw new OopsException();
            }));
            var rsp = client().writeReadTextUntilNewlines(post("not empty"));
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(OopsException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions();
            logRecorder().assertAwait(DEBUG,
                "Closing the child because client aborted the exchange.");
        }
        
        @Test
        void maxErrorResponses() throws IOException, InterruptedException {
            server().add("/", GET().apply(req -> badRequest().toBuilder()
                    // This header would have caused the server to close the connection,
                    // but we want to run many "failed" responses
                    .removeHeaderValue("Connection", "close").build()));
            
            IORunnable sendBadRequest = () -> {
                String rsp = client().writeReadTextUntilNewlines(get());
                assertThat(rsp).startsWith("HTTP/1.1 400 Bad Request");
            };
            
            final int max = server().getConfig().maxErrorResponses();
            LOG.log(INFO, () -> "Configured max: " + max);
            
            try (var conn = client().openConnection()) {
                for (int i = max; i > 1; --i) {
                    if (LOG.isLoggable(INFO)) {
                        LOG.log(INFO, "Running #" + i);
                    }
                    sendBadRequest.run();
                    assertTrue(conn.isOpen());
                }
                LOG.log(INFO, "Running last.");
                sendBadRequest.run();
                
                logRecorder().assertAwait(
                    DEBUG, "Max number of error responses reached, closing channel.");
                assertTrue(client().serverClosedOutput());
                assertTrue(client().serverClosedInput());
            }
        }
        
        @Test
        void writingTwoFinalResponses() throws IOException, InterruptedException {
            server().add("/", GET().apply(req -> {
                channel().write(noContent());
                return text("this won't work");
            }));
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1"          + CRLF + CRLF);
            // The first one succeeded
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 204 No Content" + CRLF + CRLF);
            // The second one caused some problems
            // TODO: See below, is repeated in next test case
            logRecorder().assertAwaitRemove(
                    ERROR, """
                        Response bytes already sent, \
                        can not handle this exception (closing child).""",
                    IllegalArgumentException.class)
                .hasMessage("""
                    Request processing chain \
                    both wrote and returned a final response.""")
                .hasNoCause()
                .hasNoSuppressedExceptions();
        }
        
        /**
         * The error version of
         * {@link ExampleTest.NonPublicExamples#RequestTrailers()}.
         */
        @Test
        void requestTrailersDiscarded_exceptionNotHandled() throws IOException {
            server().add("/", POST().apply(req ->
                    text(req.body().toText())));
            var rsp = client().writeReadTextUntilEOS("""
                    POST / HTTP/1.1
                    Trailer: just to trigger discarding
                    Transfer-Encoding: chunked
                    
                    6
                    Hello\s
                    0
                    Crash Plz: Whitespace in header name!
                    
                    """);
            assertThat(rsp).isEqualTo("""
                    HTTP/1.1 200 OK\r
                    Content-Type: text/plain; charset=utf-8\r
                    Content-Length: 6\r
                    \r
                    Hello\s""");
            logRecorder().assertRemove(
                    DEBUG, "Error while discarding request trailers, shutting down the input stream.",
                    HeaderParseException.class)
                .hasToString("""
                    HeaderParseException{prev=(hex:0x68, decimal:104, char:"h"), \
                    curr=(hex:0x20, decimal:32, char:" "), pos=N/A, \
                    msg=Whitespace in header name or before colon is not accepted.}""")
                .hasNoCause()
                .hasNoSuppressedExceptions();
        }
    }
}