package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.HttpServerStopped;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import alpha.nomagichttp.testutil.functional.TestClient;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import static alpha.nomagichttp.core.mediumtest.util.TestRequests.get;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static java.lang.System.Logger.Level.DEBUG;
import static java.time.Duration.ofSeconds;
import static java.time.Instant.now;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * Server life-cycle tests.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ServerLifeCycleTest extends AbstractRealTest
{
    @Nested
    class StartStop {
        @Test
        void async() throws IOException, InterruptedException {
            // Uses HttpServer.startAsync()
            server();
            // Can open connection
            client().openConnection().close();
            stopServer();
            // Can not open connection
            assertNewConnectionIsRejected();
        }
        
        // Without the superclass support; asserting less
        @Test
        void block() throws InterruptedException, IOException {
            var server = HttpServer.create();
            var latch = new CountDownLatch(1);
            Future<Void> fut;
            // ForkJoinPool wraps the AsyncCloseExc in two layers of RuntimeException lol
            try (var vThread = newVirtualThreadPerTaskExecutor()) {
                fut = vThread.submit(() ->
                        server.start(port -> latch.countDown()));
                latch.await();
                assertThat(fut.isDone()).isFalse();
                server.stop();
                assertThat(server.isRunning()).isFalse();
            }
            assertAwaitNormalStop(fut);
        }
    }
    
    @Nested
    class Stop {
        @Test
        void activeExchangeCompletes() throws Exception {
            // Client sends the request head, not body
            // Server consumes the head, returns 100 (Continue)
            // Client calls HttpServer.stop()
            //     Returned Future is not completed
            //     Server accepts no new connections
            // Client sends the rest of the request
            //     Future completes
            
            server().add("/", POST().apply(req ->
                    text(req.body().toText())));
            
            Future<Void> fut;
            try (var _ = client().openConnection()) {
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
                
                String rsp2 = client().writeReadTextUntil(
                    "Hi!"                                     + CRLF + CRLF, "Hi!");
                assertThat(rsp2).isEqualTo(
                    "HTTP/1.1 200 OK"                         + CRLF +
                    "Content-Type: text/plain; charset=utf-8" + CRLF +
                    "Connection: close"                       + CRLF +
                    "Content-Length: 3"                       + CRLF + CRLF +
                    
                    "Hi!");
                
                // Not dependent on the closure of client's connection
                assertAwaitClosingChild();
            }
            
            assertThat(fut.get(1, SECONDS)).isNull();
            assertThat(server().isRunning()).isFalse();
            assertNewConnectionIsRejected();
        }
        
        @Test
        void inactiveExchangeAborts() throws IOException, InterruptedException {
            server();
            try (var _ = client().openConnection()) {
                // Wait for the server to confirm,
                // otherwise one can not deterministically test the log of "idling children"
                assertAwaitChildAccept();
                Instant before = now();
                stopServer();
                // Stopping should be completed more or less instantaneously
                assertThat(before.until(now()))
                    .isLessThan(ofSeconds(1));
                assertNewConnectionIsRejected();
                logRecorder().assertContainsOnlyOnce(
                    DEBUG, "Closed 1 idling children of a total 1.");
            }
        }
        
        @Test
        void graceExpires_handlerStalled() throws IOException, InterruptedException {
            var stopServer = new Semaphore(0);
            server().add("/", GET().apply(_ -> {
                stopServer.release();
                SECONDS.sleep(STOP_GRACEFUL_SECONDS + 1);
                throw new AssertionError("Thread supposed to be interrupted");
            }));
            try (var _ = client().openConnection()) {
                client().write(get());
                stopServer.acquire();
                Instant before = now();
                stopServer(false);
                // Stopping completes after a 1-second graceful period
                assertThat(before.until(now()))
                    .isGreaterThanOrEqualTo(ofSeconds(1));
                logRecorder().assertContainsOnlyOnce(
                    DEBUG, "Closing the child because thread interrupted.");
            }
        }
        
        @Test
        void graceExpires_clientStalled() throws IOException, InterruptedException {
            var stopServer = new Semaphore(0);
            server().add("/", POST().apply(req -> {
                stopServer.release();
                req.body().bytes();
                throw new AssertionError("The body is never sent");
            }));
            try (var _ = client().openConnection()) {
                client().write(
                    "POST / HTTP/1.1"     + CRLF +
                    "Content-Length: 999" + CRLF + CRLF);
                stopServer.acquire();
                Instant before = now();
                stopServer(false);
                assertThat(before.until(now()))
                    .isGreaterThanOrEqualTo(ofSeconds(1));
                // TODO: Should assertThatNoWarningOrExceptionIsLogged()
            }
        }
    }
    
    private void assertNewConnectionIsRejected() {
        var client = new TestClient(serverPort());
        // On Windows 10, msg is "Connection refused: connect"
        // On Ubuntu 20.04, msg is "Connection refused"
        assertThatThrownBy(client::openConnection)
            .isExactlyInstanceOf(ConnectException.class)
            // And on MacOS? I guess we'll find out lol
            .hasMessageStartingWith("Connection refused");
    }
}