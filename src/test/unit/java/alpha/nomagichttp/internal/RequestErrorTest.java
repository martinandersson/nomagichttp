package alpha.nomagichttp.internal;

import alpha.nomagichttp.test.Logging;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static java.lang.System.Logger.Level.ALL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of a server with exception handlers.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RequestErrorTest extends AbstractEndToEndTest
{
    @BeforeAll
    static void logEverything() {
        Logging.setLevel(RequestErrorTest.class, ALL);
    }
    
    @Test
    void not_found_default() throws IOException, InterruptedException {
        String req = "GET /404 HTTP/1.1" + CRLF + CRLF + CRLF,
               res = writeReadText(req);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    // TODO: Implement
    //       First, we need to make AbstractEndToEndTest an API with explicit
    //       control over life cycle and Server setup, rename him to
    //       "ClientOperations". The only parameter the new c-tor should depend
    //       on is the port number, which he uses to connect an embedded client to.
    //@Test
    void not_found_custom() {
        
    }
}