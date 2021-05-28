package alpha.nomagichttp.real;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.MemorizingSubscriber.Signal;
import alpha.nomagichttp.util.Publishers;
import alpha.nomagichttp.util.SubscriberFailedException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.continue_;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.real.TestRequests.get;
import static alpha.nomagichttp.real.TestRequests.post;
import static alpha.nomagichttp.real.TestRoutes.respondIsBodyEmpty;
import static alpha.nomagichttp.real.TestRoutes.respondRequestBody;
import static alpha.nomagichttp.testutil.Logging.toJUL;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofByteArray;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.CompletableFuture.failedStage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests concerning details of the server.<p>
 * 
 * Not so much "GET ..." and then expect "HTTP/1.1 200 ..." - the "casual"
 * exchange. Rather;.perhaps make semi-weird calls and expect a particular
 * server behavior. You know, details.<p>
 * 
 * Many tests will likely require a fine-grained control of the client and do
 * lots of assertions on the server's log.<p>
 * 
 * Note: life-cycle details ought to go to {@link ClientLifeCycleTest} or
 * {@link ServerLifeCycleTest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DetailTest extends AbstractRealTest
{
    @Test
    void connection_reuse() throws IOException {
        server().add(respondRequestBody());
        
        final String resHead =
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 3"                       + CRLF + CRLF;
        
        Channel ch = client().openConnection();
        try (ch) {
            String res1 = client().writeRead(post("ABC"), "ABC");
            assertThat(res1).isEqualTo(resHead + "ABC");
            
            String res2 = client().writeRead(post("DEF"), "DEF");
            assertThat(res2).isEqualTo(resHead + "DEF");
        }
    }
    
    @Test
    void request_body_discard_all() throws IOException {
        server().add(respondIsBodyEmpty());
        
        IORunnable exchange = () -> {
            String req = post("x".repeat(10)),
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
            String req = post("x".repeat(length)),
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
    
    // TODO: Respond 100 Continue thru config (subclass must expose config)
    
    // Also see MessageTest.expect100Continue_onFirstBodyAccess()
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
        assertThatLogContainsOnlyOnce(
                // First ignored 100 Continue silently logged
                rec(DEBUG, "Ignoring repeated 100 (Continue)."),
                // But any more than that and level escalates
                rec(WARNING, "Ignoring repeated 100 (Continue)."));
    }
    
    @Test
    void maxUnsuccessfulResponses() throws IOException, InterruptedException {
        server().add("/", GET().respond(badRequest()));
        
        IORunnable sendBadRequest = () -> {
            String rsp = client().writeRead(get());
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
        
        String rsp = client().writeRead(get(), "Hi");
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
        
        String rsp = client().writeRead(get());
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
        
        String rsp = client().writeRead(get(), "done");
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
    
    // TODO: This needs to go to ErrorTest; after test refactoring
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
    
    // TODO: This needs to go to ErrorTest; after test refactoring
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
    
    // See ResponseBodySubscriber.sliceIntoChunks()
    @Test
    void responseBodyBufferOversized() throws IOException {
        // End of message
        final byte eom = 9;
        
        // TODO: Need improved "HTTP message" API in TestClient
        
        // Same as ChannelByteBufferPublisher.BUF_SIZE/**/
        byte[] rspBody = new byte[16 * 1_024 + 1];
        rspBody[rspBody.length - 1] = eom;
        Response r = Responses.ok(ofByteArray(rspBody));
        server().add("/", GET().respond(r));
        
        byte[] req = ("GET / HTTP/1.1" + CRLF + CRLF).getBytes(US_ASCII);
        
        byte[] expHead =
                ("HTTP/1.1 200 OK"                       + CRLF +
                "Content-Type: application/octet-stream" + CRLF +
                "Content-Length: 16385"                  + CRLF + CRLF)
                .getBytes(US_ASCII);
        
        ByteBuffer merged = ByteBuffer.allocate(expHead.length + rspBody.length);
        for (byte b : expHead) merged.put(b);
        for (byte b : rspBody) merged.put(b);
        
        byte[] rsp = client().writeRead(req, new byte[]{eom});
        assertThat(rsp).isEqualTo(merged.array());
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
