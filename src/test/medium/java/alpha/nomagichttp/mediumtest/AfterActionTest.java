package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.HttpConstants.HeaderKey.X_CORRELATION_ID;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.serviceUnavailable;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.Logging.toJUL;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofString;
import static java.lang.String.valueOf;
import static java.lang.System.Logger.Level.ERROR;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Medium tests for after-actions.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class AfterActionTest extends AbstractRealTest
{
    @Test
    void javadoc_ex() throws IOException {
        server()
            .before("/*", (req, ch, chain) -> {
                if (req.headerIsMissingOrEmpty(X_CORRELATION_ID)) {
                    req.attributes().set(X_CORRELATION_ID, "123");
                }
                chain.proceed(); })
            .add("/:msg", GET().apply(req ->
                text(req.target().pathParam("msg")).completedStage()))
            .after("/*", (req, rsp) ->
                req.attributes().<String>getOptAny(X_CORRELATION_ID)
                   .or(() -> req.headers().firstValue(X_CORRELATION_ID))
                   .map(id -> rsp.toBuilder().header(X_CORRELATION_ID, id).build())
                   .orElse(rsp)
                   .completedStage());
        
        var ch = client().openConnection();
        try (ch) {
            var rsp1 = client().writeReadTextUntil(
                "GET /hello HTTP/1.1"                     + CRLF + CRLF, "hello");
            assertThat(rsp1).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Length: 5"                       + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "X-Correlation-ID: 123"                   + CRLF + CRLF +
                
                "hello");
            
            var rsp2 = client().writeReadTextUntilEOS(
                "GET /bye HTTP/1.1"                       + CRLF +
                "X-Correlation-ID: 456"                   + CRLF +
                "Connection: close"                       + CRLF + CRLF);
            assertThat(rsp2).isEqualTo(
                "HTTP/1.1 200 OK" + CRLF +
                "Content-Length: 3"                       + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "X-Correlation-ID: 456"                   + CRLF +
                "Connection: close"                       + CRLF + CRLF +
                
                "bye");
            
            assertThatNoWarningOrErrorIsLogged();
        }
    }
    
    @Test
    void multistage() throws IOException, InterruptedException {
        server()
            .after("/*", (req, rsp) -> rsp.toBuilder().header("X-Count", "1").build().completedStage())
            .after("/*", (req, rsp) -> {
                long v = rsp.headers().firstValueAsLong("X-Count").getAsLong();
                return rsp.toBuilder().header("X-Count", valueOf(++v)).build().completedStage();
            });
        
        var rsp = client().writeReadTextUntilNewlines(
            "GET /404 HTTP/1.1"      + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0"      + CRLF +
            "X-Count: 2"             + CRLF + CRLF);
        
        assertThatServerErrorObservedAndLogged()
                .isExactlyInstanceOf(NoRouteFoundException.class)
                .hasMessage("/404")
                .hasNoCause()
                .hasNoSuppressedExceptions();
    }
    
    @Test
    void crash() throws IOException, InterruptedException {
        // 1. NoRouteFoundException
        // 2. default error handler writes 404
        // 3. after-action crashes with IllegalStateException
        // 4. custom error handler writes 503
        
        usingErrorHandler((thr, ch, req) -> {
            try {
                throw thr;
            } catch (IllegalStateException e) {
                // Weirdly translate ISE to 503
                ch.write(serviceUnavailable().toBuilder()
                        .body(ofString(e.getMessage())).build());
            }
        });
        server().after("/", (req, rsp) -> {
            if (rsp.statusCode() != 503) {
                // Weirdly crash for everything but 503
                throw new IllegalStateException("hello");
            }
            return rsp.completedStage();
        });
        
        var rsp = client().writeReadTextUntilEOS(
            "GET / HTTP/1.1"                   + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 503 Service Unavailable" + CRLF +
            "Content-Length: 5"                + CRLF +
            "Connection: close"                + CRLF + CRLF +
            
            "hello");
        
        assertThatServerErrorObservedAndLogged()
                .isExactlyInstanceOf(NoRouteFoundException.class)
                .hasMessage("/")
                .hasNoCause()
                .hasNoSuppressedExceptions();
    }
    
    @Test
    void NullPointerException() throws IOException, InterruptedException {
        usingConfiguration()
            .maxErrorRecoveryAttempts(0);
        server()
            .add("/", GET().respond(noContent()))
            .after("/", (req, rsp) -> {
                String npe = null;
                npe.toString();
                return null;
            });
        
        var rsp = client().writeReadTextUntilEOS("GET / HTTP/1.1" + CRLF + CRLF);
        
        assertThat(rsp).isEmpty();
        assertTrue(logRecorder().await(toJUL(ERROR),
                "Error recovery attempts depleted, will close the channel. " +
                "This error is ignored.",
                NullPointerException.class));
    }
}