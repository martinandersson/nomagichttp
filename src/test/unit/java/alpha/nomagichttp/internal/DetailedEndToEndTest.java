package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.ClientOperations.CRLF;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static org.assertj.core.api.Assertions.assertThat;
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
        
        assertThat(pollError())
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
    
    @Test
    void request_body_subscriber_crash() throws IOException, InterruptedException {
        RuntimeException err = new RuntimeException("Oops.");
        
        RequestHandler crashAfterOneByte = POST().accept((req, ch) ->
            req.body().subscribe(
                new AfterByteTargetStop(1, subscriptionIgnored -> {
                    throw err; })));
        
        server().add("/", crashAfterOneByte);
        
        Channel ch = client().openConnection();
        try (ch) {
            String req = requestWithBody("Hello"),
                   res = client().writeRead(req);
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            
            assertThat(pollError()).isSameAs(err);
            assertThat(client().drain()).isEmpty();
            assertThat(ch.isOpen()).isFalse();
            
            /*
             * What just happened?
             * 
             * 1) On the server side, OnErrorCloseReadStream caught the error
             * and closed the input stream. Not the output stream and not the
             * child channel.
             * 
             * 2) Error propagates and eventually hits the default exception
             * handler, which produces Responses.internalServerError() which
             * specifies "mustCloseAfterWrite()".
             * 
             * 2) HttpExchange notices the request to close and calls
             * ChannelOperations.orderlyClose() which before closing the child
             * first shuts down the input- and output streams.
             * 
             * 3) This then gives our client an EOS to which we react by closing
             * the channel on our side. See ClientOperations.writeRead().
             * 
             * TODO: When we have a ConnectionLifeCycleTest, make reference.
             */
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
            "Content-Length: 17"                      + CRLF + CRLF +
            
            "Received HTTP/1.0");
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
     * Waits for at most 3 seconds on the server log to indicate a child was
     * accepted.
     */
    private void awaitChildAccept() throws InterruptedException {
        assertTrue(logRecorder().await(FINE, "Accepted child:"));
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
    
    @FunctionalInterface
    private interface IORunnable {
        void run() throws IOException;
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
