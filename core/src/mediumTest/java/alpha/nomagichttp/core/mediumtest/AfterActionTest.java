package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.HttpConstants.HeaderName.X_CORRELATION_ID;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.functional.Environment.isLinux;
import static java.lang.String.valueOf;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

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
                   .or(() -> req.headers().firstValue(X_CORRELATION_ID))
                   .map(id -> rsp.toBuilder().setHeader(X_CORRELATION_ID, id).build())
                   .orElse(rsp));
        
        try (var conn = client().openConnection()) {
            // Start processing the first request can take a long time on:
            if (isLinux()) {
                client().interruptReadAfter(1.5);
            }
            
            var rsp1 = client().writeReadTextUntil(
                "GET /hello HTTP/1.1"                     + CRLF + CRLF, "hello");
            assertThat(rsp1).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "X-Correlation-ID: 123"                   + CRLF +
                "Content-Length: 5"                       + CRLF + CRLF +
                
                "hello");
            
            if (isLinux()) {
                client().interruptTimeoutReset();
            }
            
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
            
            logRecorder().assertNoThrowableNorWarning();
        }
    }
    
    @Test
    void multistage() throws IOException, InterruptedException {
        server()
            .after("/*", (req, rsp) ->
                rsp.toBuilder().setHeader("X-Count", "1").build())
            .after("/*", (req, rsp) -> {
                long v = rsp.headers().firstValueAsLong("X-Count").getAsLong();
                return rsp.toBuilder().setHeader("X-Count", valueOf(++v)).build();
            });
        
        var rsp = client().writeReadTextUntilNewlines(
            "GET /404 HTTP/1.1"      + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "X-Count: 2"             + CRLF +
            "Content-Length: 0"      + CRLF + CRLF);
        
        assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(NoRouteFoundException.class)
                .hasMessage("/404")
                .hasNoCause()
                .hasNoSuppressedExceptions();
    }
    
    // No request handler, only after-action; crashes with IllegalStateException
    @Test
    void crash_1() throws IOException, InterruptedException {
        server().after("/", (req, rsp) -> {
            assertThat(rsp.statusCode())
                  .isEqualTo(404);
            throw new IllegalStateException("boom!");
        });
        var rsp = client().writeReadTextUntilEOS(
              "GET / HTTP/1.1" + CRLF + CRLF);
        assertThat(rsp)
              .isEmpty();
        // The first error is handed off to the error handler, who also logs it
        assertAwaitHandledAndLoggedExc()
              .isExactlyInstanceOf(NoRouteFoundException.class)
              .hasMessage("/")
              .hasNoCause()
              .hasNoSuppressedExceptions();
        // The subsequent after-action exception is logged, but no error handler
        logRecorder().assertAwaitRemoveError()
              .hasToString(
                  "alpha.nomagichttp.core.AfterActionException: " +
                      "java.lang.IllegalStateException: boom!");
    }
    
    // Has request handler, then after-action returns null
    @Test
    void crash_2() throws IOException {
        server()
              .add("/", GET().apply(req -> noContent()))
              .after("/", (req, rsp) -> null);
        var rsp = client()
              .writeReadTextUntilEOS("GET / HTTP/1.1" + CRLF + CRLF);
        assertThat(rsp)
              .isEmpty();
        logRecorder().assertRemoveError().hasToString(
              "alpha.nomagichttp.core.AfterActionException: " +
              "java.lang.NullPointerException");
    }
}