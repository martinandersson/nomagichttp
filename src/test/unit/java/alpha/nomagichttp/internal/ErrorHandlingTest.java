package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.Server;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.Handlers;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseBuilder;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.test.Logging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static alpha.nomagichttp.ServerConfig.DEFAULT;
import static alpha.nomagichttp.handler.Handlers.noop;
import static alpha.nomagichttp.internal.ClientOperations.CRLF;
import static alpha.nomagichttp.route.Routes.route;
import static java.lang.System.Logger.Level.ALL;
import static java.util.Arrays.stream;
import static java.util.Collections.singleton;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of a server with error handlers.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ErrorHandlingTest
{
    private static final String
            REQ_ROOT      = "GET / HTTP/1.1"    + CRLF + CRLF + CRLF,
            REQ_NOT_FOUND = "GET /404 HTTP/1.1" + CRLF + CRLF + CRLF;
    
    Server server;
    
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
    void not_found_default() throws IOException, InterruptedException {
        String res = createServerAndClient().writeRead(REQ_NOT_FOUND);
        assertThat(res).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    @Test
    void not_found_custom() throws IOException, InterruptedException {
        ErrorHandler custom = (exc, req, han) -> {
            if (exc instanceof NoRouteFoundException) {
                return new ResponseBuilder()
                        .httpVersion("HTTP/1.1")
                        .statusCode(123)
                        .reasonPhrase("Custom Not Found!")
                        .mustCloseAfterWrite()
                        .noBody()
                        .asCompletedStage();
            }
            throw exc;
        };
        
        String res = createServerAndClient(custom).writeRead(REQ_NOT_FOUND);
        assertThat(res).isEqualTo(
            "HTTP/1.1 123 Custom Not Found!" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
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
            throws IOException, InterruptedException
    {
        AtomicInteger c = new AtomicInteger();
        
        RequestHandler h1 = Handlers.GET().supply(() -> {
            if (c.incrementAndGet() < 3) {
                return response.get();
            }
            
            return ResponseBuilder.ok()
                    .header("N", Integer.toString(c.get()))
                    .noBody()
                    .asCompletedStage();
        });
        
        ErrorHandler retry = (t, r, h2) -> h2.logic().apply(r);
        
        String res = createServerAndClient(h1, retry).writeRead(REQ_ROOT);
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "N: 3" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    private ClientOperations createServerAndClient(ErrorHandler... onError) throws IOException {
        return createServerAndClient(noop(), onError);
    }
    
    private ClientOperations createServerAndClient(RequestHandler handler, ErrorHandler... onError) throws IOException {
        Iterable<Route> r = singleton(route("/", handler));
        
        Iterable<Supplier<ErrorHandler>> eh = stream(onError)
                .map(e -> (Supplier<ErrorHandler>) () -> e)
                .collect(toList());
    
        server = Server.with(DEFAULT, r, eh).start();
        return new ClientOperations(server.getPort());
    }
}