package alpha.nomagichttp.real;

import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalBodyException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.message.RequestHeadTimeoutException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.route.NoRouteFoundException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.HEAD;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.handler.RequestHandler.TRACE;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.status;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestConfig.timeoutIdleConnection;
import static alpha.nomagichttp.testutil.TestPublishers.blockSubscriber;
import static alpha.nomagichttp.testutil.TestSubscribers.onError;
import static alpha.nomagichttp.util.BetterBodyPublishers.concat;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofString;
import static java.time.Duration.ofMillis;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(pollServerError())
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
    }
    
    @Test
    void request_too_large() throws IOException, InterruptedException {
        usingConfiguration().maxRequestHeadSize(1);
        String rsp = client().writeRead(
            "AB");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 413 Entity Too Large" + CRLF +
            "Connection: close"             + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(MaxRequestHeadSizeExceededException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage(null);
        
        // TODO: assert log
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
    }
    
    /**
     * By default, server rejects clients older than HTTP/1.0.
     * 
     * @throws IOException if an I/O error occurs
     */
    @Test
    void httpVersionRejected_tooOld_byDefault() throws IOException, InterruptedException {
        for (String v : List.of("-1.23", "0.5", "0.8", "0.9")) {
             String rsp = client().writeRead(
                 "GET / HTTP/" + v               + CRLF + CRLF);
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
        }
    }
    
    /**
     * Server may be configured to reject HTTP/1.0 clients.
     * 
     * @throws IOException if an I/O error occurs
     */
    @Test
    void httpVersionRejected_tooOld_thruConfig() throws IOException, InterruptedException {
        usingConfiguration().rejectClientsUsingHTTP1_0(true);
        
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
    }
    
    @Test
    void httpVersionRejected_tooNew() throws IOException, InterruptedException {
        for (String v : List.of("2", "3", "999")) {
             String rsp = client().writeRead(
                 "GET / HTTP/" + v                         + CRLF + CRLF);
             assertThat(rsp).isEqualTo(
                 "HTTP/1.1 505 HTTP Version Not Supported" + CRLF +
                 "Content-Length: 0"                       + CRLF +
                 "Connection: close"                       + CRLF + CRLF);
             assertThat(pollServerError())
                 .isExactlyInstanceOf(HttpVersionTooNewException.class)
                 .hasNoCause()
                 .hasNoSuppressedExceptions()
                 .hasMessage(null);
        }
    }
    
    @Test
    void IllegalBodyException_inResponseToHEAD() throws IOException, InterruptedException {
        server().add("/", HEAD().respond(text("Body!")));
        
        String rsp = client().writeRead(
            "HEAD / HTTP/1.1"                    + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(IllegalBodyException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Body in response to a HEAD request.");
    }
    
    // TODO: Same here as last test, assert log
    @Test
    void IllegalBodyException_inRequestFromTRACE() throws IOException, InterruptedException {
        server().add("/", TRACE().accept((req, ch) -> {
            throw new AssertionError("Not invoked.");
        }));
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
    }
    
    @Test
    void IllegalBodyException_in1xxResponse() throws IOException, InterruptedException {
        server().add("/", GET().respond(() ->
                Response.builder(123)
                        .body(ofString("Body!"))
                        .build()
                        .completedStage()));
        
        String rsp = client().writeRead(
            "GET / HTTP/1.1"                     + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(IllegalBodyException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Presumably a body in a 1XX (Informational) response.");
    }
    
    @Test
    void ResponseRejectedException_interimIgnoredForOldClient() throws IOException, InterruptedException {
        server().add("/", GET().accept((req, ch) -> {
            ch.write(processing()); // <-- ignored...
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
        
        // TODO: but exception NOT logged. That's the "ignored" part.
    }
    
    @Test
    void RequestHeadTimeoutException() throws IOException, InterruptedException {
        // Return uber low timeout on the first poll, i.e. for the request head,
        // but use default timeout for request body and response.
        usingConfig(timeoutIdleConnection(1, ofMillis(0)));
        
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
        
        // TODO: When we extend AbstractRealTest, assert log
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
        
        // TODO: When we extend AbstractRealTest, assert log
    }
    
    // RequestBodyTimeoutException_caughtByApp() ??
    // Super tricky to do deterministically without also blocking the test. Skipping for now.
    
    // Low-level write timeout by InterruptedByTimeoutException?
    // Same. Can't do deterministically. Need to mock the channel.
    
    @Test
    void ResponseTimeoutException_fromPipeline() throws IOException, InterruptedException {
        usingConfig(timeoutIdleConnection(3, ofMillis(0)));
        server().add("/", GET().accept((ign,ored) -> {}));
        
        String rsp = client().writeRead(
            "GET / HTTP/1.1"                   + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 503 Service Unavailable" + CRLF +
            "Content-Length: 0"                + CRLF +
            "Connection: close"                + CRLF + CRLF);
        assertThat(pollServerError())
            .isExactlyInstanceOf(ResponseTimeoutException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Gave up waiting on a response.");
        
        // TODO: When we extend AbstractRealTest, assert log
    }
    
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