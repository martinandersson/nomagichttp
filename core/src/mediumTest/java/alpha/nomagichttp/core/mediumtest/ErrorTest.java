package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.DecoderException;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.MaxRequestHeadSizeException;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.ReadTimeoutException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestLineParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.message.UnsupportedTransferCodingException;
import alpha.nomagichttp.route.AmbiguousHandlerException;
import alpha.nomagichttp.route.MediaTypeNotAcceptedException;
import alpha.nomagichttp.route.MediaTypeUnsupportedException;
import alpha.nomagichttp.route.MethodNotAllowedException;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import alpha.nomagichttp.util.Throwing;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.channels.InterruptedByTimeoutException;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.HEAD;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.handler.RequestHandler.TRACE;
import static alpha.nomagichttp.mediumtest.util.TestRequests.get;
import static alpha.nomagichttp.mediumtest.util.TestRequests.post;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.status;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
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
 * Tests in this class usually provoke errors, run asserts on errors delivered
 * to the error handler, and of course, assert the response.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ErrorTest extends AbstractRealTest
{
    private static final System.Logger LOG
            = System.getLogger(ErrorTest.class.getPackageName());
    
    private static final
        Throwing.Function<Request, Response, Exception> NOP = request -> null;
    
    private static final class OopsException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        
        OopsException() {
            // super()
        }
        
        OopsException(String msg) {
            super(msg);
        }
    }
    
    @Test
    void RequestLineParseException() throws IOException, InterruptedException {
        server();
        String rsp = client().writeReadTextUntilEOS(
            "GET / H T T P ....");
        assertThat(rsp).isEqualTo("""
             HTTP/1.1 400 Bad Request\r
             Connection: close\r
             Content-Length: 0\r\n\r\n""");
        assertThat(pollServerError())
            .isExactlyInstanceOf(RequestLineParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Whitespace in HTTP-version not accepted.");
        logRecorder()
            .assertNoThrowable();
    }
    
    @Test
    void HeaderParseException() throws IOException, InterruptedException {
        server();
        String rsp = client().writeReadTextUntilEOS("""
             GET / HTTP/1.1\r
             H e a d e r: Oops!\r\n""");
        assertThat(rsp).isEqualTo("""
             HTTP/1.1 400 Bad Request\r
             Connection: close\r
             Content-Length: 0\r\n\r\n""");
        assertThat(pollServerError())
            .isExactlyInstanceOf(HeaderParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                HeaderParseException{\
                prev=(hex:0x48, decimal:72, char:"H"), \
                curr=(hex:0x20, decimal:32, char:" "), pos=17, \
                msg=Whitespace in header name or before colon is not accepted.}""");
        logRecorder()
            .assertNoThrowable();
    }
    
    @Test
    void HttpVersionParseException() throws IOException, InterruptedException {
        server();
        String rsp = client().writeReadTextUntilNewlines(
            "GET / Oops"               + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 400 Bad Request" + CRLF +
            "Connection: close"        + CRLF +
            "Content-Length: 0"        + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(HttpVersionParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("No forward slash.");
        logRecorder()
            .assertNoThrowable();
    }
    
    // By default, server rejects clients older than HTTP/1.0
    @ParameterizedTest
    @CsvSource({"-1.23,false", "0.5,false", "0.8,false", "0.9,true"})
    void HttpVersionTooOldException_lessThan1_0(String version, boolean hasLiteral)
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
        var throwable = assertThat(pollServerError())
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
        logRecorder()
            .assertNoThrowable();
    }
    
    // Server may be configured to reject old clients
    @Test
    void HttpVersionTooOldException_eq1_0()
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
        assertThat(pollServerError())
            .isExactlyInstanceOf(HttpVersionTooOldException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
        logRecorder()
            .assertNoThrowable();
    }
    
    // Some newer versions are currently not supported
    @ParameterizedTest
    @CsvSource({"2,true", "3,true", "999,false"})
    void HttpVersionTooNewException(String version, boolean hasLiteral)
            throws IOException, InterruptedException
    {
        server();
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/" + version                   + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 505 HTTP Version Not Supported" + CRLF +
            "Connection: close"                       + CRLF +
            "Content-Length: 0"                       + CRLF + CRLF);
        var throwable = assertThat(pollServerError())
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
        logRecorder()
            .assertNoThrowable();
    }
    
    @Test
    void BadHeaderException() throws IOException, InterruptedException {
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
        assertThat(pollServerError())
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
        logRecorder()
            .assertNoThrowable();
    }
    
    @Test
    void BadRequestException() throws IOException, InterruptedException {
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
        assertThat(pollServerError())
            .isExactlyInstanceOf(BadRequestException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Content-Length and Transfer-Encoding are both present.");
        logRecorder()
            .assertNoThrowable();
    }
    
    @Test
    void UnsupportedTransferCodingException() throws IOException, InterruptedException {
        server();
        String rsp = client().writeReadTextUntilNewlines("""
            GET / HTTP/1.1\r
            Transfer-Encoding: blabla, chunked\r\n\r
            """);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 501 Not Implemented\r
            Connection: close\r
            Content-Length: 0\r\n\r\n""");
        assertThat(pollServerError())
            .isExactlyInstanceOf(UnsupportedTransferCodingException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Unsupported Transfer-Encoding: blabla");
        logRecorder()
            .assertNoThrowable();
    }
    
    @Test
    void MaxRequestHeadSizeException()
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
    
    @Test
    void NoRouteFoundException_default()
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
    void NoRouteFoundException_custom() throws IOException {
        usingErrorHandler((exc, chain, req) ->
            exc instanceof NoRouteFoundException ?
                    status(499, "Custom Not Found!") :
                    chain.proceed());
        server();
        String rsp = client().writeReadTextUntilNewlines(
            "GET /404 HTTP/1.1"              + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 499 Custom Not Found!" + CRLF +
            "Content-Length: 0"              + CRLF + CRLF);
        logRecorder().assertNoThrowable();
    }
    
    // Expect 405 (Method Not Allowed)
    @Test
    void MethodNotAllowedException_BLABLA()
            throws IOException, InterruptedException
    {
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
    void MethodNotAllowedException_OPTIONS()
            throws IOException, InterruptedException
    {
        server().add("/",
                GET().apply(req -> internalServerError()),
                POST().apply(req -> internalServerError()));
        String rsp = client().writeReadTextUntilNewlines(
                "OPTIONS / HTTP/1.1"              + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
                "HTTP/1.1 204 No Content"         + CRLF +
                "Allow: OPTIONS, POST, GET"       + CRLF + CRLF);
        assertThat(pollServerError())
                .isExactlyInstanceOf(MethodNotAllowedException.class)
                .hasMessage("No handler found for method token \"OPTIONS\".");
        logRecorder().assertNoThrowableNorWarning();
    }
    
    @Test
    void MediaTypeParseException() throws IOException, InterruptedException {
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
    void MediaTypeNotAcceptedException() throws IOException, InterruptedException {
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
    void MediaTypeUnsupportedException() throws IOException, InterruptedException {
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
    
    @Test
    void AmbiguousHandlerException() throws IOException, InterruptedException {
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
    void DecoderException_handledByApp() throws IOException {
        // Must kick off the subscription to provoke the error
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
        logRecorder()
            .assertNoThrowable();
    }
    
    @Test
    void DecoderException_handledByErrorHandler()
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
        logRecorder()
            .assertNoThrowable();
        assertThat(pollServerErrorNow())
            .isExactlyInstanceOf(DecoderException.class)
            .hasNoSuppressedExceptions()
            .hasMessage("""
                java.lang.NumberFormatException: \
                not a hexadecimal digit: "X" = 88""");
    }
    
    @Test
    void IllegalRequestBodyException()
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
        assertThat(pollServerError())
            .isExactlyInstanceOf(IllegalRequestBodyException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Body in a TRACE request.");
        logRecorder()
            .assertNoThrowable();
    }
    
    @Test
    void IllegalResponseBodyException_in1xxResponse()
            throws IOException, InterruptedException
    {
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
    void IllegalResponseBodyException_inResponseToHEAD()
            throws IOException, InterruptedException
    {
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
    
    @Test
    void interimResponseIgnoredForOldClient() throws IOException, InterruptedException {
        server().add("/", GET().apply(req -> {
            channel().write(processing()); // <-- rejected
            return text("Done!");
        }));
        // ... because "HTTP/1.0"
        String rsp = client().writeReadTextUntil(
            "GET / HTTP/1.0"                          + CRLF + CRLF, "Done!");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Connection: close"                       + CRLF +
            "Content-Length: 5"                       + CRLF + CRLF +
            
            "Done!");
        logRecorder()
            .assertAwait(DEBUG,
                "Ignoring 1XX (Informational) response for HTTP/1.0 client.")
            .assertNoThrowableNorWarning();
    }
    
    // TODO: fileResponse_blockedByWriteLock
    // Link in JavaDoc MessageTest fileResponse_okay
    
    @Disabled("We need to implement timeouts for v-threads")
    @Test
    void ReadTimeoutException_duringHead()
            throws IOException, InterruptedException
    {
        usingConfiguration()
            .timeoutRead(ofMillis(1));
        String rsp = client().writeReadTextUntilNewlines(
            // Server waits for CRLF + CRLF, but times out instead
            "GET / HTTP...");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 408 Request Timeout" + CRLF +
            "Content-Length: 0"            + CRLF +
            "Connection: close"            + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(ReadTimeoutException.class)
            .hasNoSuppressedExceptions()
            // The message is set by the Java runtime lol
            .hasMessage("java.nio.channels.InterruptedByTimeoutException")
            .hasCauseExactlyInstanceOf(InterruptedByTimeoutException.class);
        logRecorder()
            .assertNoThrowable();
    }
    
    // ReadTimeoutException_duringBody ?
    // No deterministic way of doing it, as we can not change the timeout
    // half-way. There's no difference, however, between a ReadTimeoutException
    // occurring during a body subscription versus any other type. So ignoring.
    
    @Disabled("We need to implement timeouts for v-threads")
    @Test
    void ResponseTimeoutException_fromPipeline()
            throws IOException, InterruptedException
    {
        usingConfiguration()
            .timeoutResponse(ofMillis(1));
        server().add("/",
             GET().apply(NOP));
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1"                   + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 503 Service Unavailable" + CRLF +
            "Content-Length: 0"                + CRLF +
            "Connection: close"                + CRLF + CRLF);
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(ResponseTimeoutException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Gave up waiting on a response.");
    }
    
    @Disabled("We need to implement timeouts for v-threads")
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void ResponseTimeoutException_fromResponse(
            boolean blockImmediately, boolean addContentLength)
            throws IOException, InterruptedException
    {
//        Semaphore unblock = new Semaphore(0);
//        Response rsp1 = blockImmediately ?
//                ok(blockSubscriberUntil(unblock)) :
//                // If not immediately, send 1 char first, then block
//                ok(concat(ofString("x"), blockSubscriberUntil(unblock)));
//
//        // With content-length, pipeline's timer op will subscribe to the
//        // response body. Without, timer will subscribe to chunked encoder op.
//        if (addContentLength) {
//            rsp1 = rsp1.toBuilder().addHeader("Content-Length", "123").build();
//        }
//
//        usingConfiguration()
//            .timeoutResponse(ofMillis(1));
//        server().add("/",
//            GET().respond(rsp1));
//
//        // The objective of this test is to ensure the connection closes.
//        // Otherwise, our client would time out on this side.
//        String rsp2 = client().writeReadTextUntilEOS(
//                "GET / HTTP/1.1" + CRLF + CRLF);
//
//        assertThat(rsp2).satisfiesAnyOf(
//                v -> assertThat(v).isEmpty(),
//                // any portion of 200 (OK)
//                v -> assertThat(v).startsWith("H"),
//                v -> assertThat(v).startsWith("HTTP/1.1 503 (Service Unavailable)"));
//
//        // Must unblock request thread to guarantee log
//        unblock.release();
//
//        var thr1 = pollServerError();
//        if (thr1 == null) {
//            // Response body timeout caused ResponseBodySubscriber to close channel
//            // (nothing delivered to error handler, but was logged)
//            var r = logRecorder().take(
//                    WARNING, """
//                      Child channel is closed for writing. \
//                      Can not resolve this error. \
//                      HTTP exchange is over.""",
//                    ResponseTimeoutException.class);
//            assertThat(r).isNotNull();
//            assertThat(r.getThrown()).hasMessage(
//                "Gave up waiting on a response body bytebuffer.");
//            // No other error
//            logRecorder().assertThatNoErrorWasLogged();
//        } else {
//            // Error handler can get one or even two timeout exceptions
//            Consumer<Throwable> assertThr = t -> {
//                assertThat(t)
//                    .isExactlyInstanceOf(ResponseTimeoutException.class)
//                    .hasNoCause()
//                    .hasNoSuppressedExceptions();
//                assertThat(t.getMessage()).satisfiesAnyOf(
//                    v -> assertThat(v).isEqualTo("Gave up waiting on a response."),
//                    v -> assertThat(v).isEqualTo("Gave up waiting on a response body bytebuffer."));
//            };
//            assertThr.accept(thr1);
//            var thr2 = pollServerError(1, SECONDS);
//            if (thr2 != null) {
//                assertThr.accept(thr2);
//                assertThat(thr2.getMessage()).isNotEqualTo(thr1.getMessage());
//            }
//            logRecorder().assertThatLogContainsOnlyOnce(
//                    rec(ERROR, "Base error handler received:", thr1));
//            if (thr2 != null) {
//            logRecorder().assertThatLogContainsOnlyOnce(
//                    rec(ERROR, "Base error handler received:", thr2));
//            }
//        }
//
//        // Read away a trailing (failed) attempt to write 503 (Service Unavailable)
//        // (as to not fail a subsequent test assertion on the log)
//        logRecorder().timeoutAfter(1, SECONDS);
//        try {
//            logRecorder().assertAwait(
//                WARNING,
//                    "Child channel is closed for writing. " +
//                    "Can not resolve this error. " +
//                    "HTTP exchange is over.",
//                ClosedChannelException.class);
//        } catch (AssertionError ignored) {
//            // Empty
//        }
    }
    
    @Test
    void Special_errorHandlerFails() throws IOException, InterruptedException {
        usingErrorHandler((thr, ch, req) -> {
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
        logRecorder().assertAwaitRemoveError()
              .isExactlyInstanceOf(OopsException.class)
              .hasMessage("first")
              .hasNoCause()
              .hasSuppressedException(new OopsException("second"));
    }
    
    // channel remains fully open
    // TODO: Link ClientLifeCycleTest#clientClosesChannel_serverReceivedPartialHead
    @Test
    void Special_requestBodyConsumerFails() throws IOException, InterruptedException {
        onErrorAssert(OopsException.class, ch ->
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
    void Special_maxErrorResponses() throws IOException, InterruptedException {
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
    void Special_writingTwoFinalResponses()
            throws IOException, InterruptedException
    {
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
                    can not handle this error (closing child).""",
                IllegalArgumentException.class)
            .hasMessage("""
                Request processing chain \
                both wrote and returned a final response.""")
            .hasNoCause()
            .hasNoSuppressedExceptions();
    }
    
    /** The error version of {@link ExampleTest#RequestTrailers()}. */
    @Test
    void Special_requestTrailersDiscarded_errorNotHandled() throws IOException {
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