package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.route.AmbiguousHandlerException;
import alpha.nomagichttp.route.MediaTypeNotAcceptedException;
import alpha.nomagichttp.route.MediaTypeUnsupportedException;
import alpha.nomagichttp.route.MethodNotAllowedException;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.status;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

///  Tests of request-to-handler routing.
final class RoutingTest extends AbstractRealTest
{
    @Test
    void ambiguousHandlerExc() throws IOException, InterruptedException {
        server().add("/",
            GET().produces("text/plain").apply(_ -> null),
            GET().produces("text/html").apply(_ -> null));
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1\n\n");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 500 Internal Server Error\r
            Content-Length: 0\r\n\r\n""");
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(AmbiguousHandlerException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                Ambiguous: [\
                DefaultRequestHandler{method="GET", \
                consumes="<nothing and all>", produces="text/plain", logic=?}, \
                DefaultRequestHandler{method="GET", \
                consumes="<nothing and all>", produces="text/html", logic=?}]""");
    }
    
    @Test
    void mediaTypeNotAcceptedExc() throws IOException, InterruptedException {
        server().add("/",
            GET().produces("text/blabla").apply(_ -> null));
        String rsp = client().writeReadTextUntilNewlines("""
            GET / HTTP/1.1\r
            Accept: text/different\r\n\r\n""");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 406 Not Acceptable\r
            Content-Length: 0\r\n\r\n""");
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(MediaTypeNotAcceptedException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                No handler found matching \
                "Accept: text/different" header in request.""");
    }
    
    @Test
    void mediaTypeUnsupportedExc() throws IOException, InterruptedException {
        server().add("/",
            GET().consumes("text/blabla").apply(_ -> null));
        String rsp = client().writeReadTextUntilNewlines("""
            GET / HTTP/1.1
            Content-Type: text/different\n\n""");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 415 Unsupported Media Type\r
            Content-Length: 0\r\n\r\n""");
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(MediaTypeUnsupportedException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("""
                No handler found matching \
                "Content-Type: text/different" header in request.""");
    }
    
    @Nested
    class MethodNotAllowedExc {
        // Expect 405 (Method Not Allowed)
        @Test
        void BLABLA() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(_ -> internalServerError()),
                POST().apply(_ -> internalServerError()));
            String rsp = client().writeReadTextUntilNewlines(
                "BLABLA / HTTP/1.1"               + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 405 Method Not Allowed" + CRLF +
                // Actually, order is not defined, let's see for how long this test pass
                "Allow: POST, GET"                + CRLF +
                "Content-Length: 0"               + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(MethodNotAllowedException.class)
                .hasMessage("No handler found for method token \"BLABLA\".")
                .hasNoCause()
                .hasNoSuppressedExceptions();
        }
        
        // ...but if the method is OPTIONS, the default configuration implements it
        @Test
        void OPTIONS() throws IOException, InterruptedException {
            server().add("/",
                    GET().apply(_ -> internalServerError()),
                    POST().apply(_ -> internalServerError()));
            String rsp = client().writeReadTextUntilNewlines(
                    "OPTIONS / HTTP/1.1"              + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                    "HTTP/1.1 204 No Content"         + CRLF +
                    "Allow: OPTIONS, POST, GET"       + CRLF + CRLF);
            assertThat(pollServerException())
                    .isExactlyInstanceOf(MethodNotAllowedException.class)
                    .hasMessage("No handler found for method token \"OPTIONS\".");
        }
    }
    
    @Nested
    class NoRouteFoundExc {
        @Test
        void handledByBase()
                throws IOException, InterruptedException
        {
            server();
            String rsp = client().writeReadTextUntilNewlines(
                "GET /404 HTTP/1.1"      + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 404 Not Found" + CRLF +
                "Content-Length: 0"      + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(NoRouteFoundException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("/404");
        }
        
        @Test
        void handledByApp() throws IOException {
            usingExceptionHandler((exc, chain, req) ->
                exc instanceof NoRouteFoundException ?
                        status(499, "Custom Not Found!") :
                        chain.proceed());
            server();
            String rsp = client().writeReadTextUntilNewlines(
                "GET /404 HTTP/1.1"              + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 499 Custom Not Found!" + CRLF +
                "Content-Length: 0"              + CRLF + CRLF);
        }
    }
}
