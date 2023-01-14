package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.EndOfStreamException;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.DecoderException;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.ReadTimeoutException;
import alpha.nomagichttp.message.RequestLineParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.message.UnsupportedTransferCodingException;
import alpha.nomagichttp.route.AmbiguousHandlerException;
import alpha.nomagichttp.route.MediaTypeNotAcceptedException;
import alpha.nomagichttp.route.MediaTypeUnsupportedException;
import alpha.nomagichttp.route.MethodNotAllowedException;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.AbstractRealTest;
import alpha.nomagichttp.testutil.IORunnable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.LogRecord;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.HEAD;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.handler.RequestHandler.TRACE;
import static alpha.nomagichttp.handler.RequestHandler.builder;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.status;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.Assertions.assertSubscriberOnError;
import static alpha.nomagichttp.testutil.LogRecords.rec;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestPublishers.blockSubscriberUntil;
import static alpha.nomagichttp.testutil.TestRequests.get;
import static alpha.nomagichttp.testutil.TestRequests.post;
import static alpha.nomagichttp.testutil.TestSubscribers.onError;
import static alpha.nomagichttp.testutil.TestSubscribers.onNext;
import static alpha.nomagichttp.testutil.TestSubscribers.onNextAndComplete;
import static alpha.nomagichttp.testutil.TestSubscribers.onSubscribe;
import static alpha.nomagichttp.util.BetterBodyPublishers.concat;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofString;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.time.Duration.ofMillis;
import static java.util.List.of;
import static java.util.concurrent.CompletableFuture.failedStage;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests concerning server errors.<p>
 * 
 * Tests in this class usually provoke errors, run asserts on errors delivered
 * to the error handler, and of course, assert the response.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ErrorTest extends AbstractRealTest
{
    private static final System.Logger LOG
            = System.getLogger(ErrorTest.class.getPackageName());
    
    private static final RequestHandler.Logic NOP = (ign,ored) -> {};
    
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
        String rsp = client().writeReadTextUntilEOS(
            "GET / H T T P ....");
        assertThat(rsp).isEqualTo("""
             HTTP/1.1 400 Bad Request\r
             Content-Length: 0\r
             Connection: close\r\n\r\n""");
        assertThat(pollServerError())
            .isExactlyInstanceOf(RequestLineParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Whitespace in HTTP-version not accepted.");
        logRecorder()
            .assertThatNoErrorWasLogged();
    }
    
    @Test
    void HeaderParseException() throws IOException, InterruptedException {
        String rsp = client().writeReadTextUntilEOS("""
             GET / HTTP/1.1\r
             H e a d e r: Oops!\r\n""");
        assertThat(rsp).isEqualTo("""
             HTTP/1.1 400 Bad Request\r
             Content-Length: 0\r
             Connection: close\r\n\r\n""");
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
            .assertThatNoErrorWasLogged();
    }
    
    @Test
    void HttpVersionParseException() throws IOException, InterruptedException {
        String rsp = client().writeReadTextUntilNewlines(
            "GET / Oops"               + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 400 Bad Request" + CRLF +
            "Content-Length: 0"        + CRLF + 
            "Connection: close"        + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(HttpVersionParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("No forward slash.");
        logRecorder()
            .assertThatNoErrorWasLogged();
    }
    
    // By default, server rejects clients older than HTTP/1.0
    @ParameterizedTest
    @ValueSource(strings = {"-1.23", "0.5", "0.8", "0.9"})
    void HttpVersionTooOldException_lessThan1_0(String version)
            throws IOException, InterruptedException
    {
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/" + version         + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 426 Upgrade Required" + CRLF +
            "Content-Length: 0"             + CRLF +
            "Upgrade: HTTP/1.1"             + CRLF +
            "Connection: Upgrade"           + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(HttpVersionTooOldException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
        logRecorder()
            .assertThatNoErrorWasLogged();
    }
    
    // Server may be configured to reject also HTTP/1.0 clients
    @Test
    void HttpVersionTooOldException_eq1_0()
            throws IOException, InterruptedException
    {
        usingConfiguration()
            .rejectClientsUsingHTTP1_0(true);
        String rsp = client().writeReadTextUntilNewlines(
            "GET /not-found HTTP/1.0"       + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.0 426 Upgrade Required" + CRLF +
            "Content-Length: 0"             + CRLF +
            "Upgrade: HTTP/1.1"             + CRLF +
            "Connection: close"             + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(HttpVersionTooOldException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
        logRecorder()
            .assertThatNoErrorWasLogged();
    }
    
    // Some newer versions are currently not supported
    @ParameterizedTest
    @ValueSource(strings = {"2", "3", "999"})
    void HttpVersionTooNewException(String version)
            throws IOException, InterruptedException
    {
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/" + version                   + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 505 HTTP Version Not Supported" + CRLF +
            "Content-Length: 0"                       + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(HttpVersionTooNewException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
        logRecorder()
            .assertThatNoErrorWasLogged();
    }
    
    @Test
    void BadHeaderException() throws IOException, InterruptedException {
        server().add("/",
            GET().accept(NOP));
        String rsp = client().writeReadTextUntilEOS("""
            GET / HTTP/1.1\r
            Content-Type: BOOM!\r\n\r
            """);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 400 Bad Request\r
            Content-Length: 0\r
            Connection: close\r\n\r\n""");
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
            .assertThatNoErrorWasLogged();
    }
    
    @Test
    void BadRequestException() throws IOException, InterruptedException {
        String rsp = client().writeReadTextUntilEOS("""
            GET / HTTP/1.1\r
            Transfer-Encoding: chunked\r
            Content-Length: 123\r\n\r
            """);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 400 Bad Request\r
            Content-Length: 0\r
            Connection: close\r\n\r\n""");
        assertThat(pollServerError())
            .isExactlyInstanceOf(BadRequestException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Content-Length and Transfer-Encoding present.");
        logRecorder()
            .assertThatNoErrorWasLogged();
    }
    
    @Test
    void UnsupportedTransferCodingException() throws IOException, InterruptedException {
        String rsp = client().writeReadTextUntilNewlines("""
            GET / HTTP/1.1\r
            Transfer-Encoding: blabla, chunked\r\n\r
            """);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 501 Not Implemented\r
            Content-Length: 0\r\n\r\n""");
        assertThat(pollServerError())
            .isExactlyInstanceOf(UnsupportedTransferCodingException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Unsupported Transfer-Encoding: blabla");
        logRecorder()
            .assertThatNoErrorWasLogged();
    }
    
    @Test
    void MaxRequestHeadSizeExceededException()
            throws IOException, InterruptedException
    {
        usingConfiguration()
            .maxRequestHeadSize(1);
        String rsp = client().writeReadTextUntilNewlines(
            "AB");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 413 Entity Too Large\r
            Content-Length: 0\r
            Connection: close\r\n\r\n""");
        assertThatServerErrorObservedAndLogged()
            .isExactlyInstanceOf(MaxRequestHeadSizeExceededException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
    }
    
    @Test
    void NoRouteFoundException_default()
            throws IOException, InterruptedException
    {
        String rsp = client().writeReadTextUntilNewlines(
            "GET /404 HTTP/1.1"      + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0"      + CRLF + CRLF);
        assertThatServerErrorObservedAndLogged()
            .isExactlyInstanceOf(NoRouteFoundException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("/404");
    }
    
    @Test
    void NoRouteFoundException_custom() throws IOException {
        usingErrorHandler((exc, ch, req) -> {
            if (exc instanceof NoRouteFoundException) {
                ch.write(status(499, "Custom Not Found!"));
                return;
            }
            throw exc;
        });
        String rsp = client().writeReadTextUntilNewlines(
            "GET /404 HTTP/1.1"              + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 499 Custom Not Found!" + CRLF + CRLF);
        logRecorder().assertThatNoErrorWasLogged();
    }
    
    // Expect 405 (Method Not Allowed)
    @Test
    void MethodNotAllowedException_BLABLA()
            throws IOException, InterruptedException
    {
        server().add("/",
            GET().respond(internalServerError()),
            POST().respond(internalServerError()));
        
        String rsp = client().writeReadTextUntilNewlines(
            "BLABLA / HTTP/1.1"               + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 405 Method Not Allowed" + CRLF +
            "Content-Length: 0"               + CRLF +
            // Actually, order is not defined, let's see for how long this test pass
            "Allow: POST, GET"                + CRLF + CRLF);
        assertThatServerErrorObservedAndLogged()
            .isExactlyInstanceOf(MethodNotAllowedException.class)
            .hasMessage("No handler found for method token \"BLABLA\".");
    }
    
    // ...but if the method is OPTIONS, the default configuration implements it
    @Test
    void MethodNotAllowedException_OPTIONS()
            throws IOException, InterruptedException
    {
        server().add("/",
                GET().respond(internalServerError()),
                POST().respond(internalServerError()));
        
        String rsp = client().writeReadTextUntilNewlines(
                "OPTIONS / HTTP/1.1"              + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
                "HTTP/1.1 204 No Content"         + CRLF +
                "Allow: OPTIONS, POST, GET"       + CRLF + CRLF);
        
        assertThat(pollServerError())
                .isExactlyInstanceOf(MethodNotAllowedException.class)
                .hasMessage("No handler found for method token \"OPTIONS\".");
        
        assertThatNoWarningOrErrorIsLogged();
    }
    
    @Test
    void MediaTypeParseException() throws IOException, InterruptedException {
        server().add("/", GET().accept((ign,ored) ->
            MediaType.parse("BOOM!")));
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1\n\n");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 500 Internal Server Error\r
            Content-Length: 0\r\n\r\n""");
        var thr = logRecorder()
            .assertAwaitFirstLogErrorOf(MediaTypeParseException.class);
        assertThat(thr)
            .isExactlyInstanceOf(MediaTypeParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                Can not parse "BOOM!". \
                Expected exactly one forward slash in <type/subtype>.""");
        assertThat(thr)
            .isSameAs(pollServerErrorNow());
    }
    
    @Test
    void MediaTypeNotAcceptedException() throws IOException, InterruptedException {
        server().add("/",
            GET().produces("text/blabla").accept(NOP));
        String rsp = client().writeReadTextUntilNewlines("""
            GET / HTTP/1.1\r
            Accept: text/different\r\n\r\n""");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 406 Not Acceptable\r
            Content-Length: 0\r\n\r\n""");
        var thr = logRecorder()
            .assertAwaitFirstLogErrorOf(MediaTypeNotAcceptedException.class);
        assertThat(thr)
            .isExactlyInstanceOf(MediaTypeNotAcceptedException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                No handler found matching \
                "Accept: text/different" header in request.""");
        assertThat(thr)
            .isSameAs(pollServerErrorNow());
    }
    
    @Test
    void MediaTypeUnsupportedException() throws IOException, InterruptedException {
        server().add("/",
            GET().consumes("text/blabla").accept(NOP));
        String rsp = client().writeReadTextUntilNewlines("""
            GET / HTTP/1.1
            Content-Type: text/different\n\n""");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 415 Unsupported Media Type\r
            Content-Length: 0\r\n\r\n""");
        var thr = logRecorder()
            .assertAwaitFirstLogErrorOf(MediaTypeUnsupportedException.class);
        assertThat(thr)
            .isExactlyInstanceOf(MediaTypeUnsupportedException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                No handler found matching \
                "Content-Type: text/different" header in request.""");
        assertThat(thr)
            .isSameAs(pollServerErrorNow());
    }
    
    @Test
    void AmbiguousHandlerException() throws IOException, InterruptedException {
        server().add("/",
            GET().produces("text/plain").accept(NOP),
            GET().produces("text/html").accept(NOP));
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1\n\n");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 500 Internal Server Error\r
            Content-Length: 0\r\n\r\n""");
        var thr = logRecorder()
            .assertAwaitFirstLogErrorOf(AmbiguousHandlerException.class);
        assertThat(thr)
            .isExactlyInstanceOf(AmbiguousHandlerException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                Ambiguous: [\
                DefaultRequestHandler{method="GET", \
                consumes="<nothing and all>", produces="text/plain", logic=?}, \
                DefaultRequestHandler{method="GET", \
                consumes="<nothing and all>", produces="text/html", logic=?}]""");
        assertThat(thr)
            .isSameAs(pollServerErrorNow());
    }
    
    @Test
    void DecoderException_deliveredToApp() throws IOException {
        // Must kick off the subscription to provoke the error
        server().add("/",
            GET().apply(req -> req.body().toText()
                    .exceptionally(Throwable::toString)
                    .thenApply(Responses::text)));
        String rsp = client().writeReadTextUntilEOS("""
            GET / HTTP/1.1
            Transfer-Encoding: chunked
            Connection: close
            
            ABCDEX.....\n""");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 200 OK\r
            Content-Length: 110\r
            Content-Type: text/plain; charset=utf-8\r
            Connection: close\r
            \r
            alpha.nomagichttp.message.DecoderException: \
            java.lang.NumberFormatException: \
            not a hexadecimal digit: "X" = 88""");
        logRecorder()
            .assertThatNoErrorWasLogged();
    }
    
    @Test
    void DecoderException_deliveredToErrorHandler()
            throws IOException
    {
        server().add("/",
            GET().apply(req -> req.body().toText()
                    // no .exceptionally(), pass through to pipeline
                    .thenApply(Responses::text)));
        String rsp = client().writeReadTextUntilEOS("""
            GET / HTTP/1.1
            Transfer-Encoding: chunked
            
            ABCDEX.....\n""");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 400 Bad Request\r
            Content-Length: 0\r
            Connection: close\r\n\r\n""");
        logRecorder()
            .assertThatNoErrorWasLogged();
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
            TRACE().accept((req, ch) -> { throw new AssertionError("Not invoked."); }));
        String rsp = client().writeReadTextUntilNewlines(
            "TRACE / HTTP/1.1"         + CRLF +
            "Content-Length: 1"        + CRLF + CRLF +
            
            "X");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 400 Bad Request" + CRLF +
            "Content-Length: 0"        + CRLF +
            "Connection: close"        + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(IllegalRequestBodyException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Body in a TRACE request.");
        logRecorder()
            .assertThatNoErrorWasLogged();
    }
    
    @Test
    void IllegalResponseBodyException_in1xxResponse()
            throws IOException, InterruptedException
    {
        server().add("/",
            GET().respond(() -> Response.builder(123)
                    .body(ofString("Body!"))
                    .build().completedStage()));
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1"                     + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        assertThatServerErrorObservedAndLogged()
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
            HEAD().respond(text("Body!")));
        String rsp = client().writeReadTextUntilNewlines(
            "HEAD / HTTP/1.1"                    + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        assertThatServerErrorObservedAndLogged()
            .isExactlyInstanceOf(IllegalResponseBodyException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Body in response to a HEAD request.");
    }
    
    @Test
    void ResponseRejectedException_interimIgnoredForOldClient()
            throws IOException, InterruptedException
    {
        server().add("/", GET().accept((req, ch) -> {
            ch.write(processing()); // <-- rejected
            ch.write(text("Done!"));
        }));
        
        // ... because "HTTP/1.0"
        String rsp = client().writeReadTextUntil(
            "GET / HTTP/1.0"                          + CRLF + CRLF, "Done!");
        assertThat(rsp).isEqualTo(
            "HTTP/1.0 200 OK"                         + CRLF +
            "Content-Length: 5"                       + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Connection: close"                       + CRLF + CRLF +
            
            "Done!");
        
        // Exception delivered to error handler, yes
        assertThat(pollServerError())
                .isExactlyInstanceOf(ResponseRejectedException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("HTTP/1.0 does not support 1XX (Informational) responses.");
        
        // but exception NOT logged. That's the "ignored" part.
        logRecorder().assertThatNoErrorWasLogged();
    }
    
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
            .assertThatNoErrorWasLogged();
    }
    
    // ReadTimeoutException_duringBody ?
    // No deterministic way of doing it, as we can not change the timeout
    // half-way. There's no difference, however, between a ReadTimeoutException
    // occurring during a body subscription versus any other type. So ignoring.
    
    @Test
    void ResponseTimeoutException_fromPipeline()
            throws IOException, InterruptedException
    {
        usingConfiguration()
            .timeoutResponse(ofMillis(1));
        server().add("/",
             GET().accept(NOP));
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1"                   + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 503 Service Unavailable" + CRLF +
            "Content-Length: 0"                + CRLF +
            "Connection: close"                + CRLF + CRLF);
        assertThatServerErrorObservedAndLogged()
            .isExactlyInstanceOf(ResponseTimeoutException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Gave up waiting on a response.");
    }
    
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void ResponseTimeoutException_fromResponse(
            boolean blockImmediately, boolean addContentLength)
            throws IOException, InterruptedException
    {
        Semaphore unblock = new Semaphore(0);
        Response rsp1 = blockImmediately ?
                ok(blockSubscriberUntil(unblock)) :
                // If not immediately, send 1 char first, then block
                ok(concat(ofString("x"), blockSubscriberUntil(unblock)));
        
        // With content-length, pipeline's timer op will subscribe to the
        // response body. Without, timer will subscribe to chunked encoder op.
        if (addContentLength) {
            rsp1 = rsp1.toBuilder().addHeader("Content-Length", "123").build();
        }
        
        usingConfiguration()
            .timeoutResponse(ofMillis(1));
        server().add("/",
            GET().respond(rsp1));
        
        // The objective of this test is to ensure the connection closes.
        // Otherwise, our client would time out on this side.
        String rsp2 = client().writeReadTextUntilEOS(
                "GET / HTTP/1.1" + CRLF + CRLF);
        
        assertThat(rsp2).satisfiesAnyOf(
                v -> assertThat(v).isEmpty(),
                // any portion of 200 (OK)
                v -> assertThat(v).startsWith("H"),
                v -> assertThat(v).startsWith("HTTP/1.1 503 (Service Unavailable)"));
        
        // Must unblock request thread to guarantee log
        unblock.release();
        
        var thr1 = pollServerError();
        if (thr1 == null) {
            // Response body timeout caused ResponseBodySubscriber to close channel
            // (nothing delivered to error handler, but was logged)
            var r = logRecorder().take(
                    WARNING, """
                      Child channel is closed for writing. \
                      Can not resolve this error. \
                      HTTP exchange is over.""",
                    ResponseTimeoutException.class);
            assertThat(r).isNotNull();
            assertThat(r.getThrown()).hasMessage(
                "Gave up waiting on a response body bytebuffer.");
            // No other error
            logRecorder().assertThatNoErrorWasLogged();
        } else {
            // Error handler can get one or even two timeout exceptions
            Consumer<Throwable> assertThr = t -> {
                assertThat(t)
                    .isExactlyInstanceOf(ResponseTimeoutException.class)
                    .hasNoCause()
                    .hasNoSuppressedExceptions();
                assertThat(t.getMessage()).satisfiesAnyOf(
                    v -> assertThat(v).isEqualTo("Gave up waiting on a response."),
                    v -> assertThat(v).isEqualTo("Gave up waiting on a response body bytebuffer."));
            };
            assertThr.accept(thr1);
            var thr2 = pollServerError(1, SECONDS);
            if (thr2 != null) {
                assertThr.accept(thr2);
                assertThat(thr2.getMessage()).isNotEqualTo(thr1.getMessage());
            }
            logRecorder().assertThatLogContainsOnlyOnce(
                    rec(ERROR, "Default error handler received:", thr1));
            if (thr2 != null) {
            logRecorder().assertThatLogContainsOnlyOnce(
                    rec(ERROR, "Default error handler received:", thr2));
            }
        }
        
        // Read away a trailing (failed) attempt to write 503 (Service Unavailable)
        // (as to not fail a subsequent test assertion on the log)
        logRecorder().timeoutAfter(1, SECONDS);
        try {
            logRecorder().assertAwait(
                WARNING,
                    "Child channel is closed for writing. " +
                    "Can not resolve this error. " +
                    "HTTP exchange is over.",
                ClosedChannelException.class);
        } catch (AssertionError ignored) {
            // Empty
        }
    }
    
    // Is treated as a new error, having suppressed the previous one
    @Test
    void Special_errorHandlerFails() throws IOException, InterruptedException {
        Consumer<Throwable> assertSecond = thr -> {
            assertThat(thr)
                    .isExactlyInstanceOf(OopsException.class)
                    .hasMessage("second")
                    .hasNoCause();
            var oops = thr.getSuppressed()[0];
            assertOopsException(oops);
        };
        usingConfiguration().maxErrorRecoveryAttempts(2);
        AtomicInteger n = new AtomicInteger();
        usingErrorHandler((thr, ch, req) -> {
            if (n.incrementAndGet() == 1) {
                assertOopsException(thr);
                assertThat(ch.isEverythingOpen()).isTrue();
                throw new OopsException("second");
            } else {
                assertSecond.accept(thr);
                // Pass forward to superclass' collector
                throw thr;
            }
        });
        server().add("/", GET().respond(() -> {
            throw new OopsException();
        }));
        
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1"                     + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        
        assertSecond.accept(pollServerError());
    }
    
    // onSubscribe() fails, error goes to ErrorHandler, channel remains fully open
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void Special_requestBodySubscriberFails_onSubscribe(String method) throws IOException, InterruptedException {
        var sub = onSubscribe(s -> { throw new OopsException(); });
        
        onErrorAssert(OopsException.class, ch ->
            assertThat(ch.isEverythingOpen()).isTrue());
        server().add("/", builder(method).accept((req, ch) ->
            req.body().subscribe(sub)));
        
        String req = switch (method) {
            case "GET"  -> get();
            case "POST" -> post("not empty");
            default -> throw new AssertionError();
        };
        
        String rsp = client().writeReadTextUntilNewlines(req);
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        
        var s = sub.signals();
        assertThat(s).hasSize(1);
        assertSame(s.get(0).methodName(), ON_SUBSCRIBE);
        
        assertOopsException(pollServerError());
    }
    
    // onNext() fails, error goes to ErrorHandler, channel's read stream is closed
    @Test
    void Special_requestBodySubscriberFails_onNext() throws IOException, InterruptedException {
        // Read stream closed in ChannelByteBufferPublisher.afterSubscriberFinished()
        var sub = onNext(i -> { throw new OopsException(); });
        
        onErrorAssert(OopsException.class, ch ->
            assertThat(ch.isOpenForReading()).isFalse()); // FALSE!
        server().add("/", POST().accept((req, ch) ->
                req.body().subscribe(sub)));
        
        String rsp = client().writeReadTextUntilNewlines(post("not empty"));
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + 
            "Connection: close"                  + CRLF + CRLF);
        
        assertThat(logRecorderStop()).extracting(
                LogRecord::getLevel, LogRecord::getMessage, LogRecord::getThrown)
            .contains(rec(DEBUG,
                "Subscription terminated. Will close the channel's read stream."));
        
        var s = sub.signals();
        assertThat(s).hasSize(2);
        
        assertSame(s.get(0).methodName(), ON_SUBSCRIBE);
        assertSame(s.get(1).methodName(), ON_NEXT);
        
        assertOopsException(pollServerError());
    }
    
    /**
     * onError() fails, error is logged but otherwise ignored.
     * 
     * @see ClientLifeCycleTest#clientClosesChannel_serverReceivedPartialHead
     */
    @Test
    void Special_requestBodySubscriberFails_onError() throws IOException, InterruptedException {
        var oops = new OopsException("is logged but not re-thrown");
        var sub = onError(eos  -> { throw oops; });
        var ch1 = new ArrayBlockingQueue<ClientChannel>(1);
        
        server().add("/", POST().accept((req, ch2) -> {
            req.body().subscribe(sub);
            ch1.add(ch2);
        }));
        
        var fakeBodyReq =
            "POST / HTTP/1.1"                         + CRLF +
            "Accept: text/plain; charset=utf-8"       + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 999999"                  + CRLF + CRLF;
        
        // Close after headers
        client().write(fakeBodyReq);
        
        logRecorder().assertAwait(ERROR,
                "Subscriber.onError() returned exceptionally. " +
                "This new error is only logged but otherwise ignored.",
                OopsException.class);
        
        assertSubscriberOnError(sub)
            .isExactlyInstanceOf(EndOfStreamException.class)
            .hasMessage(null)
            .hasNoCause()
            .hasNoSuppressedExceptions();
        
        // Superclass awaits clean HTTP exchange closure
        // (ideally of course the subscriber would've sent a response lol)
        ch1.poll(1, SECONDS).closeSafe();
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void Special_requestBodySubscriberFails_onComplete(String method)
            throws IOException, InterruptedException
    {
        // For both HTTP methods, the error will exceptionally complete the invocation chain.
        // And thus, be sent off to the error handler, which responds code 500.
        
        switch (method) {
            case "GET" ->
                // For GET, the subscriber subscribes to Publishers.empty().
                // As far as the channel is concerned, everything stays the same.
                onErrorAssert(OopsException.class, ch ->
                    assertThat(ch.isEverythingOpen()).isTrue());
            case "POST" ->
                // POST subscribes directly to ChannelByteBufferPublisher, which shuts down.
                onErrorAssert(OopsException.class, ch ->
                    assertThat(ch.isOpenForReading()).isFalse());
            default ->
                throw new AssertionError();
        }
        
        var sub = onNextAndComplete(
                PooledByteBufferHolder::discard,
                ()   -> { throw new OopsException(); });
        
        server().add("/", builder(method).accept((req, ch) ->
            req.body().subscribe(sub)));
        
        String req = switch (method) {
            case "GET"  -> get();
            case "POST" -> post("1"); // Small body to make sure we stay within one ByteBuffer
            default -> throw new AssertionError();
        };
        
        var actualRsp = client().writeReadTextUntilNewlines(req);
        
        var expectedRsp =
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF;
        
        switch (method) {
            case "GET"  -> expectedRsp += CRLF;
            // Response pipeline observes the half-closed state induced by ChannelByteBufferPublisher
            case "POST" -> expectedRsp += "Connection: close" + CRLF + CRLF;
            default     -> throw new AssertionError();
        }
        
        assertThat(actualRsp).isEqualTo(expectedRsp);
        
        List<MethodName> expected = switch (method) {
            case "GET"  -> of(ON_SUBSCRIBE, ON_COMPLETE);
            case "POST" -> of(ON_SUBSCRIBE, ON_NEXT, ON_COMPLETE);
            default -> throw new AssertionError();
        };
        
        assertThat(sub.methodNames()).isEqualTo(expected);
        assertOopsException(pollServerError());
    }
    
    @Test
    void Special_maxUnsuccessfulResponses() throws IOException, InterruptedException {
        server().add("/", GET().respond(badRequest().toBuilder()
                // This header would have caused the server to close the connection,
                // but we want to run many "failed" responses
                .removeHeaderValue("Connection", "close").build()));
        
        IORunnable sendBadRequest = () -> {
            String rsp = client().writeReadTextUntilNewlines(get());
            assertThat(rsp).startsWith("HTTP/1.1 400 Bad Request");
        };
        
        final int max = server().getConfig().maxUnsuccessfulResponses();
        LOG.log(INFO, () -> "Max unsuccessful: " + max);
        
        try (Channel ch = client().openConnection()) {
            for (int i = max; i > 1; --i) {
                if (LOG.isLoggable(INFO)) {
                    LOG.log(INFO, "Running #" + i);
                }
                sendBadRequest.run();
                assertTrue(ch.isOpen());
            }
            LOG.log(INFO, "Running last.");
            sendBadRequest.run();
            
            logRecorder().assertAwaitChildClose();
            assertTrue(client().serverClosedOutput());
            assertTrue(client().serverClosedInput());
        }
    }
    
    @Test
    void Special_afterHttpExchange_responseIsLoggedButIgnored()
            throws IOException, InterruptedException
    {
        server().add("/", GET().accept((req, ch) -> {
            ch.write(noContent());
            ch.write(Response.builder(123).build());
        }));
        
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1"          + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 204 No Content" + CRLF + CRLF);
        
        logRecorder().assertAwait(
            WARNING, "HTTP exchange not active. This response is ignored: DefaultResponse{statusCode=123");
        
        // Superclass asserts no error sent to error handler
    }
    
    @Test
    void Special_afterHttpExchange_responseExceptionIsLoggedButIgnored()
            throws IOException, InterruptedException
    {
        server().add("/", GET().accept((req, ch) -> {
            ch.write(noContent());
            ch.write(failedStage(new RuntimeException("Oops!")));
        }));
        
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1"          + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 204 No Content" + CRLF + CRLF);
        
        logRecorder().assertAwait(WARNING, """
            Application's response stage completed exceptionally, but final \
            response has already been sent. This error does not propagate \
            anywhere.""");
        
        // Superclass asserts no error sent to error handler
    }
    
    /** The error version of {@link ExampleTest#RequestTrailers()}. */
    @Test
    void Special_requestTrailers_errorNotHandled() throws IOException, InterruptedException {
        server().add("/", POST().apply(req -> req.body().toText()
                .thenApply(Responses::text)));
        var rsp = client().writeReadTextUntilEOS("""
                POST / HTTP/1.1
                Transfer-Encoding: chunked
                
                6
                Hello\s
                0
                Crash Plz: Whitespace in header name!
                
                """);
        assertThat(rsp).isEqualTo("""
                HTTP/1.1 200 OK\r
                Content-Length: 6\r
                Content-Type: text/plain; charset=utf-8\r
                Connection: close\r
                \r
                Hello\s""");
        // The trailer exception effectively closed the channel,
        // but no other effect than a logged warning
        logRecorder().assertThatNoErrorWasLogged();
        logRecorder().assertAwait(WARNING, """
            Request trailers finished exceptionally: \
            HeaderParseException{prev=(hex:0x68, decimal:104, char:"h"), \
            curr=(hex:0x20, decimal:32, char:" "), pos=N/A, \
            msg=Whitespace in header name or before colon is not accepted.}""");
    }
    
    private void assertOopsException(Throwable oops) {
        assertThat(oops)
                .isExactlyInstanceOf(OopsException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions();
    }
}