package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.event.HttpServerStopped;
import alpha.nomagichttp.testutil.AbstractRealTest;
import alpha.nomagichttp.testutil.TestClient;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.nio.channels.Channel;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
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
        Channel ch = client().openConnection();
        try (ch; var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            String rsp1 = client().writeReadTextUntilNewlines(
                "POST / HTTP/1.1"                        + CRLF +
                "Content-Length: 3"                      + CRLF +
                "Content-Type: text/plain;charset=utf-8" + CRLF +
                "Expect: 100-continue"                   + CRLF + CRLF);
            
            assertThat(rsp1).isEqualTo(
                "HTTP/1.1 100 Continue" + CRLF + CRLF);
            
            var toContinue = new Semaphore(1);
            server().events().on(HttpServerStopped.class, x -> {
                toContinue.release();
            });
            fut = exec.submit(() -> {
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
                "Content-Length: 3"                       + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF + CRLF +
                
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
                .isExactlyInstanceOf(ConnectException.class);
    }
}