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

import static alpha.nomagichttp.HttpServer.create;
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
    HttpServer s;
    
    @BeforeAll
    static void setLogging() {
        Logging.setLevel(ALL);
    }
    
    @AfterEach
    void stopServer() throws IOException {
        if (s != null) {
            s.stopNow();
        }
    }
    
    @Test
    void not_found_default() throws IOException {
        s = create().start();
        String r = new ClientOperations(s).writeRead(
            "GET /404 HTTP/1.1" + CRLF + CRLF);
        
        assertThat(r).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0"      + CRLF + CRLF);
    }
    
    @Test
    void not_found_custom() throws IOException {
        ErrorHandler eh = (exc, ch, req, han) -> {
            if (exc instanceof NoRouteFoundException) {
                ch.write(Response.builder()
                               .statusCode(123)
                               .reasonPhrase("Custom Not Found!")
                               .mustCloseAfterWrite(true)
                               .build()
                               .completedStage());
            }
            throw exc;
        };
        
        s = create(eh).start();
        String res = new ClientOperations(s).writeRead(
            "GET /404 HTTP/1.1" + CRLF + CRLF);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 123 Custom Not Found!" + CRLF + CRLF);
    }
    
    /** Request handler fails synchronously. */
    @Test
    void retry_failed_request_sync() throws IOException {
        firstTwoRequestsResponds(() -> { throw new RuntimeException(); });
    }
    
    /** Returned stage completes exceptionally. */
    @Test
    void retry_failed_request_async() throws IOException {
        firstTwoRequestsResponds(() -> failedFuture(new RuntimeException()));
    }
    
    private void firstTwoRequestsResponds(Supplier<CompletionStage<Response>> response)
            throws IOException
    {
        AtomicInteger c = new AtomicInteger();
        
        RequestHandler h1 = RequestHandlers.GET().apply(requestIgnored -> {
            if (c.incrementAndGet() < 3) {
                return response.get();
            }
            
            return Response.Builder.ok()
                                   .header("N", Integer.toString(c.get()))
                                   .addHeader("Content-Length", "0")
                                   .build()
                                   .completedStage();
        });
        
        ErrorHandler retry = (t, ch, r, h2) -> h2.logic().accept(r, ch);
        
        s = create(retry).add("/", h1).start();;
        String r = new ClientOperations(s).writeRead(
            "GET / HTTP/1.1" + CRLF + CRLF);
        
        assertThat(r).isEqualTo(
            "HTTP/1.1 200 OK"   + CRLF +
            "N: 3"              + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    @Test
    void httpVersionBad() throws IOException {
        s = create().start();;
        String res = new ClientOperations(s).writeRead(
            "GET / Ooops" + CRLF + CRLF);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 400 Bad Request" + CRLF +
            "Content-Length: 0"        + CRLF + CRLF);
    }
    
    /**
     * By default, server rejects clients older than HTTP/1.0.
     * 
     * @throws IOException if an I/O error occurs
     */
    @Test
    void httpVersionRejected_tooOld_byDefault() throws IOException {
        s = create().start();;
        ClientOperations c = new ClientOperations(s);
        
        for (String v : List.of("-1.23", "0.5", "0.8", "0.9")) {
             String res = c.writeRead(
                 "GET / HTTP/" + v + CRLF + CRLF);
             
             assertThat(res).isEqualTo(
                 "HTTP/1.1 426 Upgrade Required" + CRLF +
                 "Upgrade: HTTP/1.1"             + CRLF +
                 "Connection: Upgrade"           + CRLF +
                 "Content-Length: 0"             + CRLF + CRLF);
        }
    }
    
    /**
     * Server may be configured to reject HTTP/1.0 clients.
     * 
     * See {@link DetailedEndToEndTest#http_1_0()} for the inverse test case. 
     * 
     * @throws IOException if an I/O error occurs
     */
    @Test
    void httpVersionRejected_tooOld_thruConfig() throws IOException {
        HttpServer.Config rejectHttp1_0 = new HttpServer.Config(){
            @Override public boolean rejectClientsUsingHTTP1_0() {
                return true;
            }
        };
        
        s = create(rejectHttp1_0).start();;
        String r = new ClientOperations(s).writeRead(
            "GET /not-found HTTP/1.0" + CRLF + CRLF);
        
        assertThat(r).isEqualTo(
            "HTTP/1.0 426 Upgrade Required" + CRLF +
            "Upgrade: HTTP/1.1"             + CRLF +
            "Connection: Upgrade"           + CRLF +
            "Content-Length: 0"             + CRLF + CRLF);
    }
    
    @Test
    void httpVersionRejected_tooNew() throws IOException {
        s = create().start();;
        ClientOperations c = new ClientOperations(s);
        
        for (String v : List.of("2", "3", "999")) {
             String r = c.writeRead(
                 "GET / HTTP/" + v + CRLF + CRLF);
             
             assertThat(r).isEqualTo(
                 "HTTP/1.1 505 HTTP Version Not Supported" + CRLF +
                 "Content-Length: 0" + CRLF + CRLF);
        }
    }
}