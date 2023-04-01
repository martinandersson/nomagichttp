package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.HttpServerStopped;
import alpha.nomagichttp.testutil.AbstractRealTest;
import alpha.nomagichttp.testutil.TestClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * Server life-cycle tests; essentially start/stop.<p>
 * 
 * For tests concerning client <i>connections</i>, see {@link
 * ClientLifeCycleTest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ServerLifeCycleTest extends AbstractRealTest
{
    @Test
    void simpleStartStop_async() throws IOException, InterruptedException {
        // Implicit startAsync() + getPort()
        server();
        // Can open connection
        client().openConnection().close();
        // Implicit stop()
        assertThatNoWarningOrErrorIsLogged();
        // Can not open connection
        assertNewConnectionIsRejected();
    }
    
    // Without the superclass support; asserting less
    @Test
    void simpleStartStop_block() throws InterruptedException, IOException {
        var server = HttpServer.create();
        var latch = new CountDownLatch(1);
        Future<Void> fut;
        // ForkJoinPool wraps the AsyncCloseExc in two layers of RuntimeException lol
        try (var exec = newVirtualThreadPerTaskExecutor()) {
            fut = exec.submit(() ->
                    server.start(port -> latch.countDown()));
            latch.await();
            assertThat(fut.isDone()).isFalse();
            server.stop();
            assertThat(server.isRunning()).isFalse();
        }
        assertThatServerStopsNormally(fut);
    }
    
    // Rename? start_writeHalfRequest_stop_writeTheRest
    @Test
    void serverStop_serverCompletesActiveExchange() throws Exception {
        // Client send request head, not body
        // Server consumes the head, returns 100 (Continue)
        // Client call HttpServer.stop()
        //     Returned stage is not completed
        //     Server accepts no new connections
        // Client send the rest of the request
        //     Stage completes
        
        server().add("/", POST().apply(req ->
                text(req.body().toText())));
        
        Future<Void> fut;
        try (var conn = client().openConnection()) {
            String rsp1 = client().writeReadTextUntilNewlines(
                "POST / HTTP/1.1"                        + CRLF +
                "Content-Length: 3"                      + CRLF +
                "Content-Type: text/plain;charset=utf-8" + CRLF +
                "Expect: 100-continue"                   + CRLF + CRLF);
            
            assertThat(rsp1).isEqualTo(
                "HTTP/1.1 100 Continue" + CRLF + CRLF);
            
            var toContinue = new Semaphore(0);
            server().events().on(HttpServerStopped.class, x -> {
                toContinue.release();
            });
            fut = commonPool().submit(() -> {
                server().stop();
                return null;
            });
            toContinue.acquire();
            assertThat(fut.isDone()).isFalse();
            assertThat(server().isRunning()).isFalse();
            assertNewConnectionIsRejected();
            
            String rsp2 = client().writeReadTextUntil(
                "Hi!"                                     + CRLF + CRLF, "Hi!");
            assertThat(rsp2).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Connection: close"                       + CRLF +
                "Content-Length: 3"                       + CRLF + CRLF +
                
                "Hi!");
            
            // Not dependent on the closure of this connection
            logRecorder().assertAwaitChildClose();
        }
        
        assertThat(fut.get(1, SECONDS)).isNull();
        assertThat(server().isRunning()).isFalse();
        assertNewConnectionIsRejected();
    }
    
    private void assertNewConnectionIsRejected() {
        TestClient client = new TestClient(serverPort());
        // On Windows 10, msg is "Connection refused: connect"
        // On Ubuntu 20.04, msg is "Connection refused"
        assertThatThrownBy(client::openConnection)
            .isExactlyInstanceOf(ConnectException.class)
            // And on MacOS? I guess we'll find out lol
            .hasMessageStartingWith("Connection refused");
    }
}