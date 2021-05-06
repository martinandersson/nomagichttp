package alpha.nomagichttp;

import alpha.nomagichttp.internal.AbstractEndToEndTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.Channel;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static java.util.logging.Level.FINE;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * This is the future home of client-connection life-cycle tests.<p>
 * 
 * There's a couple of them in {@code DetailedEndToEndTest}, specifically the
 * "client_closeChannel" test cases, and more. These will be reworked and moved
 * to this class. DetailedEndToEndTest should be focused on HTTP message
 * semantics.<p>
 * 
 * Then, we want to discriminate between client closing his input/output
 * streams. Server should, for example, not close channel if client closed only
 * his output stream but is still waiting on a response.<p>
 * 
 * Disconnects are easy to reproduce, for example, just run an exchange on
 * client and close. Server thought exchange would continue but receives a weird
 * IOException.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ClientLifeCycleTest extends AbstractEndToEndTest
{
    // Good src on investigating connection status
    // https://stackoverflow.com/questions/10240694/java-socket-api-how-to-tell-if-a-connection-has-been-closed/10241044#10241044
    // https://stackoverflow.com/questions/155243/why-is-it-impossible-without-attempting-i-o-to-detect-that-tcp-socket-was-grac
    
    @Test
    void http1_0_nonPersistent() throws IOException, InterruptedException {
        server().add("/", GET().respond(noContent()));
        
        Channel ch = client().openConnection();
        try (ch) {
            String rsp = client().writeRead(
                "GET / HTTP/1.0"         + CRLF +
                "Connection: keep-alive" + CRLF + CRLF); // <-- does not matter
            
            assertThat(rsp).isEqualTo(
                "HTTP/1.0 204 No Content" + CRLF +
                "Connection: close"       + CRLF + CRLF);
            
            awaitChildClose();
        }
    }
    
    @Test
    void closeChannelBeforeResponse() throws IOException, InterruptedException {
        server().add("/", GET().accept((req, ch) -> {
            ch.closeSafe();
            ch.write(noContent());
        }));
        
        Channel ch = client().openConnection();
        try (ch) {
            String rsp = client().writeRead("GET / HTTP/1.1" + CRLF + CRLF);
            
            assertThat(rsp).isEmpty();
            
            logRecorder().await(FINE,
                "Child channel is closed for writing. " +
                "Can not resolve this error. " +
                "HTTP exchange is over.");
            
            // Clean close from server will cause our end to close as well
            assertThat(ch.isOpen()).isFalse();
        }
        
        // <implicit assert that no error was delivered to the error handler>
    }
}