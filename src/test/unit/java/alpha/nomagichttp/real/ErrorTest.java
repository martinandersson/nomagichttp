package alpha.nomagichttp.real;

import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalBodyException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.message.RequestHeadTimeoutException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.MemorizingSubscriber;
import alpha.nomagichttp.util.SubscriberFailedException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.LogRecord;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.HEAD;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.handler.RequestHandler.TRACE;
import static alpha.nomagichttp.handler.RequestHandler.builder;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.status;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.real.TestRequests.get;
import static alpha.nomagichttp.real.TestRequests.post;
import static alpha.nomagichttp.testutil.Logging.toJUL;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestConfig.timeoutIdleConnection;
import static alpha.nomagichttp.testutil.TestPublishers.blockSubscriber;
import static alpha.nomagichttp.testutil.TestSubscribers.onError;
import static alpha.nomagichttp.testutil.TestSubscribers.onNextAndComplete;
import static alpha.nomagichttp.testutil.TestSubscribers.onNextAndError;
import static alpha.nomagichttp.testutil.TestSubscribers.onSubscribe;
import static alpha.nomagichttp.util.BetterBodyPublishers.concat;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofString;
import static alpha.nomagichttp.util.Subscribers.onNext;
import static java.lang.System.Logger.Level.ERROR;
import static java.time.Duration.ofMillis;
import static java.util.List.of;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests concerning server errors.<p>
 * 
 * In particular, tests here usually install custom error handlers and/or run
 * asserts on errors delivered to the error handler.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ErrorTest extends AbstractRealTest
{
    @Test
    void not_found_default() throws IOException, InterruptedException {
        String rsp = client().writeRead(
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
    void not_found_custom() throws IOException {
        usingErrorHandler((exc, ch, req, han) -> {
            if (exc instanceof NoRouteFoundException) {
                ch.write(status(499, "Custom Not Found!"));
                return;
            }
            throw exc;
        });
        String rsp = client().writeRead(
            "GET /404 HTTP/1.1"              + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 499 Custom Not Found!" + CRLF + CRLF);
        assertThatNoErrorWasLogged();
    }
    
    @Test
    void request_too_large() throws IOException, InterruptedException {
        usingConfiguration()
            .maxRequestHeadSize(1);
        String rsp = client().writeRead(
            "AB");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 413 Entity Too Large" + CRLF +
            "Connection: close"             + CRLF + CRLF);
        assertThatServerErrorObservedAndLogged()
            .isExactlyInstanceOf(MaxRequestHeadSizeExceededException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
    }
    
    /** Request handler fails synchronously. */
    @Test
    void retry_failed_request_sync() throws IOException {
        firstTwoRequestsResponds(() -> { throw new RuntimeException(); });
    }
    
    /** Returned stage completes exceptionally. */
    @Test
    void retry_failed_request_async() throws IOException {
        firstTwoRequestsResponds(() -> failedFuture(new RuntimeException()));
    }
    
    private void firstTwoRequestsResponds(Supplier<CompletionStage<Response>> response)
            throws IOException
    {
        // Always retry
        usingErrorHandler((t, ch, r, h) -> h.logic().accept(r, ch));
        
        AtomicInteger c = new AtomicInteger();
        server().add("/", GET().respond(() -> {
            if (c.incrementAndGet() < 3) {
                return response.get();
            }
            return noContent().toBuilder()
                    .header("N", Integer.toString(c.get()))
                    .build()
                    .completedStage();
        }));
        
        String rsp = client().writeRead(
            "GET / HTTP/1.1" + CRLF   + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 204 No Content" + CRLF +
            "N: 3"                    + CRLF + CRLF);
        assertThatNoErrorWasLogged();
    }
    
    @Test
    void httpVersionBad() throws IOException, InterruptedException {
        String rsp = client().writeRead(
            "GET / Ooops"              + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 400 Bad Request" + CRLF +
            "Content-Length: 0"        + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(HttpVersionParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("No forward slash.");
        assertThatNoErrorWasLogged();
    }
    
    /**
     * By default, server rejects clients older than HTTP/1.0.
     * 
     * @throws IOException if an I/O error occurs
     */
    @ParameterizedTest
    @ValueSource(strings = {"-1.23", "0.5", "0.8", "0.9"})
    void httpVersionRejected_tooOld_byDefault(String version) throws IOException, InterruptedException {
        String rsp = client().writeRead(
            "GET / HTTP/" + version         + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 426 Upgrade Required" + CRLF +
            "Upgrade: HTTP/1.1"             + CRLF +
            "Connection: Upgrade"           + CRLF +
            "Content-Length: 0"             + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(HttpVersionTooOldException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
        assertThatNoErrorWasLogged();
    }
    
    /**
     * Server may be configured to reject HTTP/1.0 clients.
     * 
     * @throws IOException if an I/O error occurs
     */
    @Test
    void httpVersionRejected_tooOld_thruConfig() throws IOException, InterruptedException {
        usingConfiguration()
            .rejectClientsUsingHTTP1_0(true);
        String rsp = client().writeRead(
            "GET /not-found HTTP/1.0"       + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.0 426 Upgrade Required" + CRLF +
            "Upgrade: HTTP/1.1"             + CRLF +
            "Connection: close"             + CRLF +
            "Content-Length: 0"             + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(HttpVersionTooOldException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
        assertThatNoErrorWasLogged();
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"2", "3", "999"})
    void httpVersionRejected_tooNew(String version) throws IOException, InterruptedException {
        String rsp = client().writeRead(
            "GET / HTTP/" + version                   + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 505 HTTP Version Not Supported" + CRLF +
            "Content-Length: 0"                       + CRLF +
            "Connection: close"                       + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(HttpVersionTooNewException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
        assertThatNoErrorWasLogged();
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void requestBodySubscriberFails_onSubscribe(String method) throws IOException, InterruptedException {
        MemorizingSubscriber<PooledByteBufferHolder> sub = new MemorizingSubscriber<>(
                // GET:  Caught by Publishers.empty()
                // POST: Caught by ChannelByteBufferPublisher > PushPullPublisher > AbstractUnicastPublisher
                onSubscribe(i -> { throw OOPS; }));
        
        server().add("/", builder(method).accept((req, ch) -> {
            req.body().subscribe(sub);
        }));
        
        String req;
        switch (method) {
            case "GET":  req = get(); break;
            case "POST": req = post("not empty"); break;
            default: throw new AssertionError();
        }
        
        String rsp = client().writeRead(req);
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        
        // TODO: Would ideally like to assert that when the error handler was called,
        //       read stream remained open. Requires subclass to export API for this.
        
        var s = sub.signals();
        assertThat(s).hasSize(2);
        assertSame(s.get(0).getMethodName(), ON_SUBSCRIBE);
        assertSame(s.get(1).getMethodName(), ON_ERROR);
        
        assertOnErrorThrowable(s.get(1), "Signalling Flow.Subscriber.onSubscribe() failed.");
        assertThatErrorHandlerCaughtOops();
    }
    
    @Test
    void requestBodySubscriberFails_onNext() throws IOException, InterruptedException {
        MemorizingSubscriber<PooledByteBufferHolder> sub = new MemorizingSubscriber<>(
                // Intercepted by DefaultRequest > OnErrorCloseReadStream
                onNext(i -> { throw OOPS; }));
        
        server().add("/", POST().accept((req, ch) -> {
            req.body().subscribe(sub);
        }));
        
        String rsp = client().writeRead(post("not empty"));
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        
        // TODO: Assert that read stream was closed before error handler called.
        //       Next statement kind of works as a substitute.
        assertThat(stopLogRecording()).extracting(LogRecord::getLevel, LogRecord::getMessage)
                .contains(tuple(toJUL(ERROR),
                        "Signalling Flow.Subscriber.onNext() failed. Will close the channel's read stream."));
        
        var s = sub.signals();
        assertThat(s).hasSize(3);
        
        assertSame(s.get(0).getMethodName(), ON_SUBSCRIBE);
        assertSame(s.get(1).getMethodName(), ON_NEXT);
        assertSame(s.get(2).getMethodName(), ON_ERROR);
        
        assertOnErrorThrowable(s.get(2), "Signalling Flow.Subscriber.onNext() failed.");
        assertThatErrorHandlerCaughtOops();
    }
    
    @Test
    void requestBodySubscriberFails_onError() throws IOException, InterruptedException {
        MemorizingSubscriber<PooledByteBufferHolder> sub = new MemorizingSubscriber<>(
                onNextAndError(
                        item -> { throw OOPS; },
                        thr  -> { throw new RuntimeException("is logged but not re-thrown"); }));
        
        server().add("/", POST().accept((req, ch) -> {
            req.body().subscribe(sub);
        }));
        
        String rsp = client().writeRead(post("not empty"));
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        
        // TODO: Assert that read stream was closed before error handler called.
        
        var log = stopLogRecording().collect(toList());
        assertThat(log).extracting(LogRecord::getLevel, LogRecord::getMessage)
                .contains(tuple(toJUL(ERROR),
                        "Signalling Flow.Subscriber.onNext() failed. Will close the channel's read stream."));
        
        LogRecord fromOnError = log.stream().filter(
                r -> r.getLevel().equals(toJUL(ERROR)) &&
                        r.getMessage().equals("Subscriber.onError() returned exceptionally. This new error is only logged but otherwise ignored."))
                .findAny()
                .get();
        
        assertThat(fromOnError.getThrown())
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("is logged but not re-thrown");
        
        var s = sub.signals();
        assertThat(s).hasSize(3);
        
        assertSame(s.get(0).getMethodName(), ON_SUBSCRIBE);
        assertSame(s.get(1).getMethodName(), ON_NEXT);
        assertSame(s.get(2).getMethodName(), ON_ERROR);
        
        assertOnErrorThrowable(s.get(2), "Signalling Flow.Subscriber.onNext() failed.");
        assertThatErrorHandlerCaughtOops();
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void requestBodySubscriberFails_onComplete(String method) throws IOException, InterruptedException {
        MemorizingSubscriber<PooledByteBufferHolder> sub = new MemorizingSubscriber<>(
                onNextAndComplete(
                        buf -> { buf.get().position(buf.get().limit()); buf.release(); }, // Discard
                        ()   -> { throw OOPS; }));
        
        server().add("/", builder(method).accept((req, ch) -> {
            req.body().subscribe(sub);
        }));
        
        String req;
        switch (method) {
            case "GET":  req = get(); break;
            case "POST": req = post("1"); break; // Small body to make sure we stay within one ByteBuffer
            default: throw new AssertionError();
        }
        
        String rsp = client().writeRead(req);
        
        assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                        "Content-Length: 0"                  + CRLF + CRLF);
        
        List<MemorizingSubscriber.Signal.MethodName> expected;
        switch (method) {
            case "GET":  expected = of(ON_SUBSCRIBE, ON_COMPLETE); break;
            case "POST": expected = of(ON_SUBSCRIBE, ON_NEXT, ON_COMPLETE); break;
            default:
                throw new AssertionError();
        }
        
        assertThat(sub.methodNames()).isEqualTo(expected);
        assertThatErrorHandlerCaughtOops();
    }
    
    private static void assertOnErrorThrowable(MemorizingSubscriber.Signal onError, String msg) {
        assertThat(onError.<Throwable>getArgument())
                .isExactlyInstanceOf(SubscriberFailedException.class)
                .hasMessage(msg)
                .hasNoSuppressedExceptions()
                .getCause()
                .isSameAs(OOPS);
    }
    
    private static final RuntimeException OOPS = new RuntimeException("Oops!");
    
    private void assertThatErrorHandlerCaughtOops() throws InterruptedException {
        assertThat(pollServerError())
                .isSameAs(OOPS)
                .hasNoCause()
                .hasNoSuppressedExceptions();
    }
    
    @Test
    void IllegalBodyException_inResponseToHEAD() throws IOException, InterruptedException {
        server().add("/",
            HEAD().respond(text("Body!")));
        String rsp = client().writeRead(
            "HEAD / HTTP/1.1"                    + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        assertThatServerErrorObservedAndLogged()
            .isExactlyInstanceOf(IllegalBodyException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Body in response to a HEAD request.");
    }
    
    @Test
    void IllegalBodyException_inRequestFromTRACE() throws IOException, InterruptedException {
        server().add("/",
            TRACE().accept((req, ch) -> { throw new AssertionError("Not invoked."); }));
        String rsp = client().writeRead(
            "TRACE / HTTP/1.1"         + CRLF +
            "Content-Length: 1"        + CRLF + CRLF +
            
            "X");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 400 Bad Request" + CRLF +
            "Content-Length: 0"        + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(IllegalBodyException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Body in a TRACE request.");
        assertThatNoErrorWasLogged();
    }
    
    @Test
    void IllegalBodyException_in1xxResponse() throws IOException, InterruptedException {
        server().add("/",
            GET().respond(() -> Response.builder(123)
                    .body(ofString("Body!"))
                    .build().completedStage()));
        String rsp = client().writeRead(
            "GET / HTTP/1.1"                     + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        assertThatServerErrorObservedAndLogged()
            .isExactlyInstanceOf(IllegalBodyException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Presumably a body in a 1XX (Informational) response.");
    }
    
    @Test
    void ResponseRejectedException_interimIgnoredForOldClient() throws IOException, InterruptedException {
        server().add("/", GET().accept((req, ch) -> {
            ch.write(processing()); // <-- rejected
            ch.write(text("Done!"));
        }));
        
        // ... because "HTTP/1.0"
        String rsp = client().writeRead(
            "GET / HTTP/1.0"                          + CRLF + CRLF, "Done!");
        assertThat(rsp).isEqualTo(
            "HTTP/1.0 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 5"                       + CRLF +
            "Connection: close"                       + CRLF + CRLF +
            
            "Done!");
        
        // Exception delivered to error handler, yes
        assertThat(pollServerError())
                .isExactlyInstanceOf(ResponseRejectedException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("HTTP/1.0 does not support 1XX (Informational) responses.");
        
        // but exception NOT logged. That's the "ignored" part.
        assertThatNoErrorWasLogged();
    }
    
    // TODO: Each timeout test case must be deterministic and not block
    
    @Test
    void RequestHeadTimeoutException() throws IOException, InterruptedException {
        // Return uber low timeout on the first poll, i.e. for the request head,
        // but use default timeout for request body and response.
        usingConfig(
            timeoutIdleConnection(1, ofMillis(0)));
        String rsp = client().writeRead(
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
        assertThatNoErrorWasLogged();
    }
    
    @Test
    void RequestBodyTimeoutException_caughtByServer() throws IOException, InterruptedException {
        usingConfig(timeoutIdleConnection(2, ofMillis(0)));
        final BlockingQueue<Throwable> appErr = new ArrayBlockingQueue<>(1);
        server()
                // The async timeout, even though instant in this case, does
                // not abort the eminent request handler invocation.
                .add("/", POST().accept((req, ch) -> {
                    try {
                        // This suffer from the same "blocked thread" problem
                        // other not-written test cases related to timeouts have.
                        // Need to figure out a better way.
                        MILLISECONDS.sleep(100);
                    } catch (InterruptedException e) {
                        appErr.add(e);
                        return;
                    }
                    // or body().toText().exceptionally(), doesn't matter
                    req.body().subscribe(onError(appErr::add));
                }));
        
        String rsp = client().writeRead(
            "POST / HTTP/1.1"              + CRLF +
            "Content-Length: 2"            + CRLF + CRLF +
            
            "1");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 408 Request Timeout" + CRLF +
            "Content-Length: 0"            + CRLF +
            "Connection: close"            + CRLF + CRLF);
        
        assertThat(appErr.poll(3, SECONDS))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Publisher was already subscribed to and is not reusable.");
        
        assertThat(pollServerError())
                .isExactlyInstanceOf(RequestBodyTimeoutException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage(null);
        
        assertThatNoErrorWasLogged();
    }
    
    // RequestBodyTimeoutException_caughtByApp() ??
    // Super tricky to do deterministically without also blocking the test. Skipping for now.
    
    // Low-level write timeout by InterruptedByTimeoutException?
    // Same. Can't do deterministically. Need to mock the channel.
    
    @Test
    void ResponseTimeoutException_fromPipeline() throws IOException, InterruptedException {
        usingConfig(
            timeoutIdleConnection(3, ofMillis(0)));
        server().add("/",
             GET().accept((does,nothing) -> {}));
        String rsp = client().writeRead(
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
    
    @Disabled // Unreliable at the moment, error handler may/may not observe ResponseTimeoutException?
    @Test
    void ResponseTimeoutException_fromResponseBody_immediately() throws IOException {
        usingConfig(timeoutIdleConnection(4, ofMillis(0)));
        server().add("/", GET().accept((req, ch) ->
                ch.write(ok(blockSubscriber()))));
        
        // Response may be empty, may be 503 (Service Unavailable).
        // What this test currently is that the client get's a response or connection closes.
        // (otherwise our client would have timed out on this side)
        String responseIgnored = client().writeRead(
                "GET / HTTP/1.1" + CRLF + CRLF);
        
        // TODO: Need to figure out how to release the permit on timeout and then assert log
    }
    
    // ResponseTimeoutException_fromResponseBody_afterOneChar?
    // No way to do deterministically, at least without tapping into the production code.
    
    @Disabled // Unreliable at the moment, error handler may/may not observe ResponseTimeoutException
    @Test
    void ResponseTimeoutException_fromResponseBody_afterOneChar() throws IOException {
        usingConfig(timeoutIdleConnection(4, ofMillis(0)));
        server().add("/", GET().accept((req, ch) ->
                ch.write(ok(concat(ofString("X"), blockSubscriber())))));
        
        String responseIgnored = client().writeRead(
                "GET / HTTP/1.1" + CRLF + CRLF, "until server close plz");
        
        // <res> may/may not contain none, parts, or all of the response
        
        // TODO: Same here, release permit and assert log.
        //       We should then also be able to assert the start of the 200 OK response?
    }
}