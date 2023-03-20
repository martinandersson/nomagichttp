package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.HttpConstants.HeaderName.X_CORRELATION_ID;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static java.lang.String.valueOf;
import static java.lang.System.Logger.Level.ERROR;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.eclipse.jetty.http.HttpStatus.NOT_FOUND_404;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Medium tests for after-actions.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class AfterActionTest extends AbstractRealTest
{
    @Test
    void javadoc_ex() throws IOException, InterruptedException {
        server()
            .before("/*", (req, chain) -> {
                if (req.headers().isMissingOrEmpty(X_CORRELATION_ID)) {
                    req.attributes().set(X_CORRELATION_ID, "123");
                }
                return chain.proceed(); })
            .add("/:msg", GET().apply(req ->
                text(req.target().pathParam("msg"))))
            .after("/*", (req, rsp) ->
                req.attributes()
                   .<String>getOptAny(X_CORRELATION_ID)
                   .or(() -> req.headers().delegate().firstValue(X_CORRELATION_ID))
                   .map(id -> rsp.toBuilder().header(X_CORRELATION_ID, id).build())
                   .orElse(rsp));
        
        var ch = client().openConnection();
        try (ch) {
            var rsp1 = client().writeReadTextUntil(
                "GET /hello HTTP/1.1"                     + CRLF + CRLF, "hello");
            assertThat(rsp1).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "X-Correlation-ID: 123"                   + CRLF +
                "Content-Length: 5"                       + CRLF + CRLF +
                
                "hello");
            
            var rsp2 = client().writeReadTextUntilEOS(
                "GET /bye HTTP/1.1"                       + CRLF +
                "X-Correlation-ID: 456"                   + CRLF +
                "Connection: close"                       + CRLF + CRLF);
            assertThat(rsp2).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "X-Correlation-ID: 456"                   + CRLF +
                "Connection: close"                       + CRLF +
                "Content-Length: 3"                       + CRLF + CRLF +
                
                "bye");
            
            assertThatNoWarningOrErrorIsLogged();
        }
    }
    
    @Test
    void multistage() throws IOException, InterruptedException {
        server()
            .after("/*", (req, rsp) ->
                rsp.toBuilder().header("X-Count", "1").build())
            .after("/*", (req, rsp) -> {
                long v = rsp.headers().delegate().firstValueAsLong("X-Count").getAsLong();
                return rsp.toBuilder().header("X-Count", valueOf(++v)).build();
            });
        
        var rsp = client().writeReadTextUntilNewlines(
            "GET /404 HTTP/1.1"      + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "X-Count: 2"             + CRLF +
            "Content-Length: 0"      + CRLF + CRLF);
        
        assertThatServerErrorObservedAndLogged()
                .isExactlyInstanceOf(NoRouteFoundException.class)
                .hasMessage("/404")
                .hasNoCause()
                .hasNoSuppressedExceptions();
    }
    
    @Test
    void crash() throws IOException, InterruptedException {
        // After-action crashes with IllegalStateException.
        // Exception is logged, then child is closed.
        server().after("/", (req, rsp) -> {
            assertThat(rsp.statusCode())
                  .isEqualTo(NOT_FOUND_404);
            throw new IllegalStateException("boom!");
        });
        var rsp = client().writeReadTextUntilEOS(
              "GET / HTTP/1.1" + CRLF + CRLF);
        assertThat(rsp)
              .isEmpty();
        // First error was handed to the error handler, who also logged it
        assertThatServerErrorObservedAndLogged()
              .isExactlyInstanceOf(NoRouteFoundException.class)
              .hasMessage("/")
              .hasNoCause()
              .hasNoSuppressedExceptions();
        // The subsequent after-action exception was logged, but no error handler
        var list = logRecorder().recordedErrors();
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.getLast()).hasToString(
            "alpha.nomagichttp.internal.AfterActionException: " +
                "java.lang.IllegalStateException: boom!");
    }
    
    @Test
    void NullPointerException() throws IOException, InterruptedException {
        server()
            .add("/", GET().apply(req -> noContent()))
            .after("/", (req, rsp) -> {
                String npe = null;
                npe.toString();
                return null;
            });
        
        var rsp = client().writeReadTextUntilEOS("GET / HTTP/1.1" + CRLF + CRLF);
        
        assertThat(rsp).isEmpty();
        assertTrue(logRecorder().await(ERROR,
                "Error recovery attempts depleted, will close the channel. " +
                "This error is ignored.",
                NullPointerException.class));
    }
}