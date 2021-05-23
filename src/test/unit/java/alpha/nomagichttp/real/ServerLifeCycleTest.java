package alpha.nomagichttp.real;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.testutil.TestClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.Channel;
import java.util.concurrent.CompletableFuture;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * Small tests of the {@link HttpServer}'s life-cycle.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ServerLifeCycleTest extends AbstractRealTest
{
    @Test
    void serverStop_serverCompletesActiveExchange() throws IOException, InterruptedException {
        // Client send request head, not body
        // Server consumes the head, returns 100 (Continue)
        // Client call HttpServer.stop()
        //     Returned stage is not completed
        //     Server accepts no new connections
        // Client send the rest of the request
        //     Stage completes
        
        server().add("/", POST().apply(req ->
                req.body().toText().thenApply(Responses::text)));
        
        CompletableFuture<Void> fut;
        Channel ch = client().openConnection();
        try (ch) {
            String rsp1 = client().writeRead(
                "POST / HTTP/1.1"                         + CRLF +
                "Content-Type: text/plain;charset=utf-8" + CRLF +
                "Content-Length: 3"                      + CRLF +
                "Expect: 100-continue"                   + CRLF + CRLF);
            
            assertThat(rsp1).isEqualTo(
                "HTTP/1.1 100 Continue" + CRLF + CRLF);
            
            fut = server().stop().toCompletableFuture();
            assertThat(fut).isNotCompleted();
            assertThat(server().isRunning()).isFalse();
            assertNewConnectionIsRejected();
            
            String rsp2 = client().writeRead("Hi!" + CRLF + CRLF, "Hi!");
            
            assertThat(rsp2).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 3"                       + CRLF +CRLF +
                
                "Hi!");
            
            awaitChildClose(); // Not dependent on the closure of this connection
        }
        
        assertThat(fut).succeedsWithin(1, SECONDS).isNull();
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