package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.MemorizingSubscriber;
import alpha.nomagichttp.testutil.MemorizingSubscriber.Signal;
import alpha.nomagichttp.util.Publishers;
import alpha.nomagichttp.util.SubscriberFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.LogRecord;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.handler.RequestHandler.builder;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.continue_;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.Logging.toJUL;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestSubscribers.onNextAndComplete;
import static alpha.nomagichttp.testutil.TestSubscribers.onNextAndError;
import static alpha.nomagichttp.testutil.TestSubscribers.onSubscribe;
import static alpha.nomagichttp.util.Subscribers.onNext;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.List.of;
import static java.util.concurrent.CompletableFuture.failedStage;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.INFO;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detailed end-to-end tests that target specific details of the API.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see SimpleEndToEndTest
 */
class DetailedEndToEndTest extends AbstractEndToEndTest
{
    @Test
    void empty_request_body() throws IOException {
        addEndpointIsBodyEmpty();
        
        String res = client().writeRead(requestWithBody(""), "true");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4"                       + CRLF + CRLF +
            
            "true");
    }
    
    /**
     * Client immediately closes the channel. Error handler is not called and no
     * other form of error logging occurs.
     * 
     * {@link RequestHeadSubscriber#asCompletionStage()}. 
     */
    @Test
    void client_closeChannel_serverReceivedNoBytes_ignored()
            throws IOException, InterruptedException, TimeoutException, ExecutionException
    {
        client().openConnection().close();
        awaitChildAccept();
        // In reality, whole test cycle over in less than 100 ms
        server().stop().toCompletableFuture().get(3, SECONDS);
        
        /*
         Just for the "record" (no pun intended), the log would as of 2021-03-21
         been something like this:
         
           {tstamp} | Test worker | INFO | {pkg}.DefaultServer initialize | Opened server channel: {...}
           {tstamp} | dead-25     | FINE | {pkg}.DefaultServer$OnAccept setup | Accepted child: {...}
           {tstamp} | Test worker | INFO | {pkg}.DefaultServer stopServer | Closed server channel: {...}
           {tstamp} | dead-24     | FINE | {pkg}.DefaultServer$OnAccept failed | Parent channel closed. Will accept no more children.
           {tstamp} | dead-24     | FINE | {pkg}.AnnounceToChannel$Handler completed | End of stream; other side must have closed. Will close channel's input stream.
           {tstamp} | dead-25     | FINE | {pkg}.AbstractUnicastPublisher accept | PollPublisher has a new subscriber: {...}
           {tstamp} | dead-25     | FINE | {pkg}.HttpExchange resolve | Client aborted the HTTP exchange.
           {tstamp} | dead-25     | FINE | {pkg}.DefaultChannelOperations orderlyClose | Closed child: {...}
         */
        assertThat(stopLogRecording()
                .mapToInt(r -> r.getLevel().intValue()))
                .noneMatch(v -> v > INFO.intValue());
        
        // that no error was thrown is asserted by super class
    }
    
    /**
     * Client writes an incomplete request and then closes the channel. Error
     * handler is called, but default handler notices that the error is due to a
     * disconnect and subsequently ignores it without logging.
     * 
     * @see ErrorHandler
     */
    @Test
    void client_closeChannel_serverReceivedSomeBytes_ignored()
            throws IOException, InterruptedException, TimeoutException, ExecutionException
    {
        client().write("XXX /incomplete");
        awaitChildAccept();
        server().stop().toCompletableFuture().get(3, SECONDS);
        
        assertThat(stopLogRecording()
                .mapToInt(r -> r.getLevel().intValue()))
                .noneMatch(v -> v > INFO.intValue());
        
        assertThat(pollServerError())
                .isExactlyInstanceOf(ClosedPublisherException.class)
                .hasMessage("EOS");
    }
    
    @Test
    void connection_reuse() throws IOException {
        addEndpointEchoBody();
        
        final String resHead =
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 3"                       + CRLF + CRLF;
        
        Channel ch = client().openConnection();
        try (ch) {
            String res1 = client().writeRead(requestWithBody("ABC"), "ABC");
            assertThat(res1).isEqualTo(resHead + "ABC");
            
            String res2 = client().writeRead(requestWithBody("DEF"), "DEF");
            assertThat(res2).isEqualTo(resHead + "DEF");
        }
    }
    
    @Test
    void request_body_discard_all() throws IOException {
        addEndpointIsBodyEmpty();
        
        IORunnable exchange = () -> {
            String req = requestWithBody("x".repeat(10)),
                   res = client().writeRead(req, "false");
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 5"                       + CRLF + CRLF +
                
                "false");
        };
        
        Channel ch = client().openConnection();
        try (ch) {
            exchange.run();
            
            // The endpoint didn't read the body contents, i.e. auto-discarded.
            // If done correctly, we should be be able to send a new request using the same connection:
            exchange.run();
        }
    }
    
    @Test
    void request_body_discard_half() throws IOException {
        // Previous test was pretty small, so why not roll with a large body
        final int length = 100_000,
                  midway = length / 2;
        
        RequestHandler discardMidway = POST().apply(req -> {
            req.body().subscribe(
                    new AfterByteTargetStop(midway, Flow.Subscription::cancel));
            return accepted().completedStage();
        });
        
        server().add("/", discardMidway);
        
        IORunnable exchange = () -> {
            String req = requestWithBody("x".repeat(length)),
                   res = client().writeRead(req);
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 202 Accepted" + CRLF +
                "Content-Length: 0"     + CRLF + CRLF);
        };
        
        Channel ch = client().openConnection();
        try (ch) {
            exchange.run();
            exchange.run();
        }
    }
    
    /**
     * Can make a HTTP/1.0 request (and get HTTP/1.0 response).<p>
     * 
     * See {@link ErrorHandlingTest} for cases related to unsupported versions.
     */
    @Test
    void http_1_0() throws IOException {
        server().add("/", GET().apply(req ->
                text("Received " + req.httpVersion()).completedStage()));
        
        String resp = client().writeRead(
                "GET / HTTP/1.0" + CRLF + CRLF, "Received HTTP/1.0");
        
        assertThat(resp).isEqualTo(
            "HTTP/1.0 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 17"                      + CRLF +
            "Connection: close"                       + CRLF + CRLF +
            
            "Received HTTP/1.0");
    }
    
    @Test
    void expect100Continue_onFirstBodyAccess() throws IOException {
        server().add("/", POST().apply(req ->
                req.body().toText().thenApply(Responses::text)));
        
        String req = "POST / HTTP/1.1"          + CRLF +
                     "Expect: 100-continue"     + CRLF +
                     "Content-Length: 2"        + CRLF +
                     "Content-Type: text/plain" + CRLF + CRLF +
                     
                     "Hi";
        
        String rsp = client().writeRead(req, "Hi");
        
        assertThat(rsp).isEqualTo(
                "HTTP/1.1 100 Continue"                   + CRLF + CRLF +
                
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 2"                       + CRLF + CRLF +
                
                "Hi");
    }
    
    // TODO: Respond 100 Continue thru config (subclass must expose config)
    
    @Test
    void expect100Continue_repeatedIgnored() throws IOException {
        server().add("/", GET().accept((req, ch) -> {
            // In response to GET without Expect and no body - app gets what app wants.
            ch.write(continue_());
            ch.write(continue_());
            ch.write(continue_());
            ch.write(accepted());
        }));
        
        String req = "GET / HTTP/1.1" + CRLF + CRLF;
        String rsp = client().writeRead(req, "Content-Length: 0" + CRLF + CRLF);
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 100 Continue"  + CRLF + CRLF +
            
            "HTTP/1.1 202 Accepted"  + CRLF +
            "Content-Length: 0"      + CRLF + CRLF);
        
        // Logging specified in JavaDoc of ClientChannel.write()
        assertThat(stopLogRecording())
            .extracting(LogRecord::getLevel, LogRecord::getMessage)
            .containsOnlyOnce(
                // First ignored 100 Continue silently logged
                tuple(toJUL(DEBUG),   "Ignoring repeated 100 (Continue)."),
                // But any more than that and level escalates
                tuple(toJUL(WARNING), "Ignoring repeated 100 (Continue)."));
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void requestBodySubscriberFails_onSubscribe(String method) throws IOException, InterruptedException {
        MemorizingSubscriber<PooledByteBufferHolder> sub = new MemorizingSubscriber<>(
                // GET:  Caught by Publishers.empty()
                // POST: Caught by ChannelByteBufferPublisher > AnnounceToSubscriber > AbstractUnicastPublisher
                onSubscribe(i -> { throw OOPS; }));
        
        server().add("/", builder(method).accept((req, ch) -> {
            req.body().subscribe(sub);
        }));
        
        String req;
        switch (method) {
            case "GET":  req = requestWithoutBody(); break;
            case "POST": req = requestWithBody("not empty"); break;
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
        
        assertTrue(s.get(0).getMethodName() == ON_SUBSCRIBE &&
                   s.get(1).getMethodName() == ON_ERROR);
        
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
        
        String rsp = client().writeRead(requestWithBody("not empty"));
        
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
        
        assertTrue(s.get(0).getMethodName() == ON_SUBSCRIBE &&
                   s.get(1).getMethodName() == ON_NEXT &&
                   s.get(2).getMethodName() == ON_ERROR);
        
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
        
        String rsp = client().writeRead(requestWithBody("not empty"));
        
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
        
        assertTrue(s.get(0).getMethodName() == ON_SUBSCRIBE &&
                   s.get(1).getMethodName() == ON_NEXT &&
                   s.get(2).getMethodName() == ON_ERROR);
        
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
            case "GET":  req = requestWithoutBody(); break;
            case "POST": req = requestWithBody("1"); break; // Small body to ensure we stay within one ByteBuff
            default: throw new AssertionError();
        }
        
        String rsp = client().writeRead(req);
        
        assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
        
        List<Signal.MethodName> exp;
        switch (method) {
            case "GET":  exp = of(ON_SUBSCRIBE, ON_COMPLETE); break;
            case "POST": exp = of(ON_SUBSCRIBE, ON_NEXT, ON_COMPLETE); break;
            default:
                throw new AssertionError();
        }
        
        assertThat(sub.methodNames()).isEqualTo(exp);
        assertThatErrorHandlerCaughtOops();
    }
    
    @Test
    void maxUnsuccessfulResponses() throws IOException, InterruptedException {
        server().add("/", GET().respond(badRequest()));
        
        IORunnable sendBadRequest = () -> {
            String rsp = client().writeRead(requestWithoutBody());
            assertThat(rsp).startsWith("HTTP/1.1 400 Bad Request");
        };
        
        try (Channel ch = client().openConnection()) {
            for (int i = server().getConfig().maxUnsuccessfulResponses(); i > 1; --i) {
                sendBadRequest.run();
                assertTrue(ch.isOpen());
            }
            sendBadRequest.run();
            
            awaitChildClose();
            assertTrue(client().serverClosedOutput());
            assertTrue(client().serverClosedInput());
        }
    }
    
    @Test
    void response_unknownLength_bodyNonEmpty() throws IOException, InterruptedException {
        server().add("/", GET().respond(
                text("Hi").toBuilder()
                           .removeHeader(CONTENT_LENGTH)
                           .build()));
        
        String rsp = client().writeRead(requestWithoutBody(), "Hi");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Connection: close"                       + CRLF + CRLF +
            
            "Hi");
        
        awaitChildClose();
    }
    
    @Test
    void response_unknownLength_bodyEmpty() throws IOException, InterruptedException {
        var empty = Publishers.just(ByteBuffer.allocate(0));
        server().add("/", GET().respond(ok(empty, "application/octet-stream", -1)));
        
        String rsp = client().writeRead(requestWithoutBody(), "Hi");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: application/octet-stream"  + CRLF +
            "Connection: close"                       + CRLF + CRLF);
        
        awaitChildClose();
    }
    
    @Test
    void responseOrderMaintained() throws IOException {
        server().add("/", GET().accept((req, ch) -> {
            CompletableFuture<Response> first  = new CompletableFuture<>();
            Response second = processing().toBuilder().header("ID", "2").build();
            CompletableFuture<Response> third = new CompletableFuture<>();
            ch.write(first);
            ch.write(second);
            ch.write(third);
            third.complete(text("done"));
            first.complete(processing().toBuilder().header("ID", "1").build());
        }));
        
        String rsp = client().writeRead(requestWithoutBody(), "done");
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 102 Processing"                 + CRLF +
            "ID: 1"                                   + CRLF + CRLF +
            
            "HTTP/1.1 102 Processing"                 + CRLF +
            "ID: 2"                                   + CRLF + CRLF +
            
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4"                       + CRLF + CRLF +
            
            "done");
    }
    
    // TODO: This needs to go to ErrorHandlingTest; after test refactoring
    @Test
    void afterHttpExchange_responseIsLoggedButIgnored() throws IOException, InterruptedException {
        server().add("/", GET().accept((req, ch) -> {
            ch.write(noContent());
            ch.write(Response.builder(123).build());
        }));
        
        String rsp = client().writeRead(
                "GET / HTTP/1.1"          + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
                "HTTP/1.1 204 No Content" + CRLF + CRLF);
        
        assertTrue(logRecorder().await(toJUL(WARNING),
                "HTTP exchange not active. This response is ignored: DefaultResponse{statusCode=123"));
        
        // Superclass asserts no error sent to error handler
    }
    
    // TODO: This needs to go to ErrorHandlingTest; after test refactoring
    @Test
    void afterHttpExchange_responseExceptionIsLoggedButIgnored() throws IOException, InterruptedException {
        server().add("/", GET().accept((req, ch) -> {
            ch.write(noContent());
            ch.write(failedStage(new RuntimeException("Oops!")));
        }));
        
        String rsp = client().writeRead(
                "GET / HTTP/1.1"          + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
                "HTTP/1.1 204 No Content" + CRLF + CRLF);
        
        assertTrue(logRecorder().await(toJUL(ERROR),
                "Application's response stage completed exceptionally, " +
                "but HTTP exchange is not active. This error does not propagate anywhere."));
        
        // Superclass asserts no error sent to error handler
    }
    
    private static void assertOnErrorThrowable(Signal onError, String msg) {
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
    
    /**
     * Add a "/" endpoint which responds a body with the value of {@link
     * Request.Body#isEmpty()}
     */
    private void addEndpointIsBodyEmpty() {
        server().add("/", POST().apply(req ->
                text(String.valueOf(req.body().isEmpty())).completedStage()));
    }
    
    /**
     * Add a "/" endpoint which responds a body with the text-contents of the
     * request body.
     */
    private void addEndpointEchoBody() {
        server().add("/", POST().apply(req ->
                req.body().toText().thenApply(Responses::text)));
    }
    
    /**
     * Make a "GET / HTTP/1.1" request.
     * 
     * @return the request
     */
    private static String requestWithoutBody() {
        return "GET / HTTP/1.1"                           + CRLF +
                "Accept: text/plain; charset=utf-8"       + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: " + 0                    + CRLF + CRLF;
    }
    
    /**
     * Make a HTTP/1.1 POST request with a body and request target "/".
     * 
     * @param body of request
     * 
     * @return the request
     */
    private static String requestWithBody(String body) {
        return "POST / HTTP/1.1"                         + CRLF +
               "Accept: text/plain; charset=utf-8"       + CRLF +
               "Content-Type: text/plain; charset=utf-8" + CRLF +
               "Content-Length: " + body.length()        + CRLF + CRLF +
               
               body;
    }
    
    private static class AfterByteTargetStop implements Flow.Subscriber<PooledByteBufferHolder>
    {
        private final long byteTarget;
        private final Consumer<Flow.Subscription> how;
        private Flow.Subscription subscription;
        private long read;
        
        AfterByteTargetStop(long byteTarget, Consumer<Flow.Subscription> how) {
            this.byteTarget = byteTarget;
            this.how = how;
        }
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            (this.subscription = subscription).request(Long.MAX_VALUE);
        }
        
        @Override
        public void onNext(PooledByteBufferHolder item) {
            assert read < byteTarget;
            ByteBuffer b = item.get();
            while (b.hasRemaining()) {
                b.get();
                if (++read == byteTarget) {
                    how.accept(subscription);
                    break;
                }
            }
            
            item.release();
        }
        
        @Override
        public void onError(Throwable throwable) {
            // Empty
        }
        
        @Override
        public void onComplete() {
            // Empty
        }
    }
}
