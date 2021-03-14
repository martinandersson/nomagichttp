package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.RequestHandlers;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.ClientOperations;
import alpha.nomagichttp.testutil.Logging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static alpha.nomagichttp.handler.RequestHandlers.noop;
import static alpha.nomagichttp.testutil.ClientOperations.CRLF;
import static java.lang.System.Logger.Level.ALL;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of a server with error handlers.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ErrorHandlingTest
{
    private static final String
            REQ_ROOT      = "GET / HTTP/1.1"    + CRLF + CRLF,
            REQ_NOT_FOUND = "GET /404 HTTP/1.1" + CRLF + CRLF;
    
    HttpServer server;
    
    @BeforeAll
    static void setLogging() {
        Logging.setLevel(ErrorHandlingTest.class, ALL);
    }
    
    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.stop();
        }
    }
    
    @Test
    void not_found_default() throws IOException {
        String res = createServerAndClient().writeRead(REQ_NOT_FOUND);
        assertThat(res).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    @Test
    void not_found_custom() throws IOException {
        ErrorHandler custom = (exc, req, han) -> {
            if (exc instanceof NoRouteFoundException) {
                return Response.builder()
                        .statusCode(123)
                        .reasonPhrase("Custom Not Found!")
                        .mustCloseAfterWrite(true)
                        .build()
                        .completedStage();
            }
            throw exc;
        };
        
        String res = createServerAndClient(custom).writeRead(REQ_NOT_FOUND);
        assertThat(res).isEqualTo(
            "HTTP/1.1 123 Custom Not Found!" + CRLF + CRLF);
    }
    
    /** Request handler fails synchronously. */
    @Test
    void retry_failed_request_sync() throws IOException, InterruptedException {
        firstTwoRequestsResponds(() -> { throw new RuntimeException(); });
    }
    
    /** Returned stage completes exceptionally. */
    @Test
    void retry_failed_request_async() throws IOException, InterruptedException {
        firstTwoRequestsResponds(() -> failedFuture(new RuntimeException()));
    }
    
    private void firstTwoRequestsResponds(Supplier<CompletionStage<Response>> response)
            throws IOException
    {
        AtomicInteger c = new AtomicInteger();
        
        RequestHandler h1 = RequestHandlers.GET().supply(() -> {
            if (c.incrementAndGet() < 3) {
                return response.get();
            }
            
            return Response.Builder.ok()
                    .header("N", Integer.toString(c.get()))
                    .addHeader("Content-Length", "0")
                    .build()
                    .completedStage();
        });
        
        ErrorHandler retry = (t, r, h2) -> h2.logic().apply(r);
        
        String res = createServerAndClient(h1, retry).writeRead(REQ_ROOT);
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "N: 3" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    @Test
    void httpVersionBad() throws IOException {
        String res = createServerAndClient().writeRead("GET / Ooops" + CRLF + CRLF);
        assertThat(res).isEqualTo(
            "HTTP/1.1 400 Bad Request" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    @Test
    void httpVersionRejected_tooOld() throws IOException {
        ClientOperations c = createServerAndClient();
        
        for (String v : List.of("-1.23", "0.5", "0.8", "0.9")) {
            String res = c.writeRead("GET / HTTP/" + v + CRLF + CRLF);
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 426 Upgrade Required" + CRLF +
                "Upgrade: HTTP/1.1" + CRLF +
                "Connection: Upgrade" + CRLF +
                "Content-Length: 0" + CRLF + CRLF);
        }
    }
    
    @Test
    void httpVersionRejected_tooNew() throws IOException {
        ClientOperations c = createServerAndClient();
        
        for (String v : List.of("2", "3", "999")) {
            String res = c.writeRead("GET / HTTP/" + v + CRLF + CRLF);
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 505 HTTP Version Not Supported" + CRLF +
                "Content-Length: 0" + CRLF + CRLF);
        }
    }
    
    private ClientOperations createServerAndClient() throws IOException {
        return createServerAndClient(null);
    }
    
    private ClientOperations createServerAndClient(ErrorHandler onError) throws IOException {
        return createServerAndClient(noop(), onError);
    }
    
    private ClientOperations createServerAndClient(RequestHandler handler, ErrorHandler onError) throws IOException {
        ErrorHandler[] eh = onError == null ?
                new ErrorHandler[0] :
                new ErrorHandler[]{ onError };
        
        server = HttpServer.create(eh).add("/", handler).start();
        return new ClientOperations(server.getLocalAddress().getPort());
    }
}