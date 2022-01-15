package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.EndOfStreamException;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.message.RequestHeadTimeoutException;
import alpha.nomagichttp.message.RequestLineParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.route.MethodNotAllowedException;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.AbstractRealTest;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.MemorizingSubscriber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
import static alpha.nomagichttp.testutil.LogRecords.rec;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestConfig.timeoutIdleConnection;
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
    
    // TODO
    void BadHeaderException() {
        
    }
    
    // TODO
    void BadRequestException() {
        
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
    
    // TODO
    void MediaTypeParseException() {
        
    }
    
    // TODO
    void MediaTypeNotAcceptedException() {
        
    }
    
    // TODO
    void MediaTypeUnsupportedException() {
        
    }
    
    // TODO
    void AmbiguousHandlerException() {
        
    }
    
    // TODO
    void DecoderException() {
        
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
            .hasMessage("Presumably a body in a 1XX (Informational) response.");
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
    void RequestHeadTimeoutException()
            throws IOException, InterruptedException
    {
        // Return uber low timeout on the first poll, i.e. for the request head,
        // but use default timeout for request body and response.
        usingConfig(
            timeoutIdleConnection(1, ofMillis(0)));
        String rsp = client().writeReadTextUntilNewlines(
            // Server waits for CRLF + CRLF, but times out instead
            "GET / HTTP...");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 408 Request Timeout" + CRLF +
            "Content-Length: 0"            + CRLF +
            "Connection: close"            + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(RequestHeadTimeoutException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
        logRecorder()
            .assertThatNoErrorWasLogged();
    }
    
    @Test
    void RequestBodyTimeoutException_beforeSubscriber()
            throws IOException, InterruptedException
    {
        usingConfig(timeoutIdleConnection(3, ofMillis(0)));
        Semaphore subscribe = new Semaphore(0);
        onErrorRun(RequestBodyTimeoutException.class, subscribe::release);
        final BlockingQueue<Throwable> appErr = new ArrayBlockingQueue<>(1);
        // The async timeout, even though instant in this case, does
        // not abort the eminent request handler invocation.
        server().add("/", POST().accept((req, ch) -> {
            try {
                subscribe.acquire();
            } catch (InterruptedException e) {
                appErr.add(e);
                return;
            }
            // or body().toText().exceptionally(), doesn't matter
            req.body().subscribe(onError(appErr::add));
        }));
        
        String rsp = client().writeReadTextUntilNewlines(
            "POST / HTTP/1.1"              + CRLF +
            "Content-Length: 2"            + CRLF + CRLF +
            
            "1");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 408 Request Timeout" + CRLF +
            "Content-Length: 0"            + CRLF +
            "Connection: close"            + CRLF + CRLF);
        
        assertThat(appErr.poll(1, SECONDS))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Publisher was already subscribed to and is not reusable.");
        
        assertThat(pollServerError())
                .isExactlyInstanceOf(RequestBodyTimeoutException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage(null);
    
        logRecorder()
                .assertThatNoErrorWasLogged();
    }
    
    // RequestBodyTimeoutException_afterSubscriber() ??
    // Super tricky to do deterministically without also blocking the test.
    // Skipping for now.
    
    // Low-level write timeout by InterruptedByTimeoutException?
    // Same. Can't do deterministically. Need to mock the channel.
    
    @Test
    void ResponseTimeoutException_fromPipeline()
            throws IOException, InterruptedException
    {
        usingConfig(
            timeoutIdleConnection(4, ofMillis(0)));
        server().add("/",
             GET().accept((does,nothing) -> {}));
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
    @ValueSource(booleans = {true, false})
    void ResponseTimeoutException_fromResponseBody(boolean blockImmediately)
            throws IOException, InterruptedException
    {
        Semaphore unblock = new Semaphore(0);
        Response rsp = blockImmediately ?
                ok(blockSubscriberUntil(unblock)) :
                // If not immediately, send 1 char first, then block
                ok(concat(ofString("X"), blockSubscriberUntil(unblock)));
        
        usingConfig(
            timeoutIdleConnection(5, ofMillis(0)));
        server().add("/",
            GET().respond(rsp));
        
        // Response may be
        //   1) empty,
        //   2) any portion of 200 (OK) - but never including the two ending CRLFs,
        //   3) 503 (Service Unavailable).
        // The objective of this test is to ensure the connection closes.
        // Otherwise, our client would time out on this side.
        String responseIgnored = client().writeReadTextUntilEOS(
                "GET / HTTP/1.1" + CRLF + CRLF);
        
        unblock.release(); // <-- must unblock request thread to guarantee log
        var fromLog = logRecorder().assertAwaitFirstLogError();
        assertThat(fromLog)
            .isExactlyInstanceOf(ResponseTimeoutException.class)
            .hasNoCause()
            // (may have suppressed ClosedChannelException)
            .hasMessage("Gave up waiting on a response body bytebuffer.");
        
        // As with the response, there's no guarantee the exception was
        // delivered to the error handler (and so must read away this error or
        // else superclass failure)
        var fromServer = pollServerErrorNow();
        if (fromServer != null) {
            assertSame(fromLog, fromServer);
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
        var sub = new MemorizingSubscriber<>(
                onSubscribe(s -> { throw new OopsException(); }));
        
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
        var sub = new MemorizingSubscriber<>(
                // Read stream closed in ChannelByteBufferPublisher.afterSubscriberFinished()
                onNext(i -> { throw new OopsException(); }));
        
        onErrorAssert(OopsException.class, ch ->
            assertThat(ch.isOpenForReading()).isFalse()); // FALSE!
        server().add("/", POST().accept((req, ch) ->
                req.body().subscribe(sub)));
        
        String rsp = client().writeReadTextUntilNewlines(post("not empty"));
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + 
            "Connection: close"                  + CRLF + CRLF);
        
        assertThat(stopLogRecording()).extracting(LogRecord::getLevel, LogRecord::getMessage)
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
        var sub = new MemorizingSubscriber<>(onError(eos  -> { throw oops; }));
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
        
        var s = sub.signals();
        assertThat(s).hasSize(2);
        
        assertSame(s.get(0).methodName(), ON_SUBSCRIBE);
        assertSame(s.get(1).methodName(), ON_ERROR);
        
        assertThat(s.get(1).<Throwable>argumentAs())
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
        
        var sub = new MemorizingSubscriber<>(onNextAndComplete(
                PooledByteBufferHolder::discard,
                ()   -> { throw new OopsException(); }));
        
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
                .removeHeaderIf("Connection", "close").build()));
        
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
    
    
    
    private void assertOopsException(Throwable oops) {
        assertThat(oops)
                .isExactlyInstanceOf(OopsException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions();
    }
}