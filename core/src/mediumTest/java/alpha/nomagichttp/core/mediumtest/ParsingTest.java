package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.RequestLineParseException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

/// Tests of parsing.
final class ParsingTest extends AbstractRealTest
{
    @Nested
    class RequestLine {
        @Test
        void httpVersion_whitespace() throws IOException, InterruptedException {
            server();
            String rsp = client().writeReadTextUntilEOS(
                "GET / H T T P ....");
            assertThat(rsp).isEqualTo("""
                 HTTP/1.1 400 Bad Request\r
                 Connection: close\r
                 Content-Length: 0\r\n\r\n""");
            assertThat(pollServerException())
                .isExactlyInstanceOf(RequestLineParseException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Whitespace in HTTP-version not accepted.");
        }
        
        @Test
        void httpVersion_gibberish() throws IOException, InterruptedException {
            server();
            String rsp = client().writeReadTextUntilNewlines(
                "GET / Oops"               + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 400 Bad Request" + CRLF +
                "Connection: close"        + CRLF +
                "Content-Length: 0"        + CRLF + CRLF);
            assertThat(pollServerException())
                .isExactlyInstanceOf(HttpVersionParseException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("No forward slash.");
        }
    }
    
    @Nested
    class HeaderName {
        ///  The trailer-equivalent test:
        /// [DiscardTest.ConnectionClosed#badTrailerDuringDiscard()]
        @Test
        void whitespace() throws IOException, InterruptedException {
            server();
            String rsp = client().writeReadTextUntilEOS("""
                 GET / HTTP/1.1\r
                 H e a d e r: Oops!\r\n""");
            assertThat(rsp).isEqualTo("""
                 HTTP/1.1 400 Bad Request\r
                 Connection: close\r
                 Content-Length: 0\r\n\r\n""");
            assertThat(pollServerException())
                .isExactlyInstanceOf(HeaderParseException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasToString("""
                    HeaderParseException{\
                    prev=(hex:0x48, decimal:72, char:"H"), \
                    curr=(hex:0x20, decimal:32, char:" "), pos=17, \
                    msg=Whitespace in header name or before colon is not accepted.}""");
        }
    }
    
    @Nested
    class HeaderValue {
        @Test
        void mediaTypeGibberish_request() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(_ -> null));
            String rsp = client().writeReadTextUntilEOS("""
                GET / HTTP/1.1\r
                Content-Type: BOOM!\r\n\r
                """);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 400 Bad Request\r
                Connection: close\r
                Content-Length: 0\r\n\r\n""");
            assertThat(pollServerException())
                .isExactlyInstanceOf(BadHeaderException.class)
                .hasMessage("Failed to parse Content-Type header.")
                .hasNoSuppressedExceptions()
                .cause()
                    .isExactlyInstanceOf(MediaTypeParseException.class)
                    .hasNoSuppressedExceptions()
                    .hasNoCause()
                    .hasMessage("""
                        Can not parse "BOOM!". \
                        Expected exactly one forward slash in <type/subtype>.""");
        }
        
        @Test
        void mediaTypeGibberish_app() throws IOException, InterruptedException {
            server().add("/", GET().apply(_ -> {
                MediaType.parse("BOOM!");
                throw new AssertionError();
            }));
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1\n\n");
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 500 Internal Server Error\r
                Content-Length: 0\r\n\r\n""");
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(MediaTypeParseException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("""
                    Can not parse "BOOM!". \
                    Expected exactly one forward slash in <type/subtype>.""");
        }
    }
}
