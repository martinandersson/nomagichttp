package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderName.TRANSFER_ENCODING;
import static alpha.nomagichttp.HttpConstants.Method.CONNECT;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.NOT_MODIFIED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.OK;
import static alpha.nomagichttp.HttpConstants.StatusCode.THREE_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.HEAD;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Response.builder;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.util.ByteBufferIterables.ofString;
import static alpha.nomagichttp.util.ByteBufferIterables.ofSupplier;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Asserts legal and illegal variants of message bodies and related headers.
/// 
/// Most common, legal variants ought to be tested by {@link ExampleTest} and
/// {@link MessageTest}. This test class is for very rare cases we don't wish to
/// make an example of, nor are we interested in [or wouldn't be able to test]
/// client compatibility.
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
class MessageFramingTest extends AbstractRealTest
{
    @Nested
    class RequestValid {
        @Test
        void GET_hasBody() throws IOException {
            server().add("/", GET().apply(
                // Echo the request body
                req -> text(req.body().toText())));
            var rsp = client().writeReadTextUntil("""
                GET / HTTP/1.1\r
                Content-Length: 3\r
                \r
                EOM""", "EOM");
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain; charset=utf-8\r
                Content-Length: 3\r
                \r
                EOM""");
        }
        
        @Test
        void POST_noBody() throws IOException {
            server().add("/", POST().apply(req -> {
                assertThat(req.body().isEmpty()).isTrue();
                return noContent();
            }));
            var rsp = client().writeReadTextUntilNewlines(
                "POST / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 204 No Content" + CRLF + CRLF);
        }
    }
    
    @Nested
    class RequestBad {
        // "If a message is received with both a Transfer-Encoding and a
        //  Content-Length header field, the Transfer-Encoding overrides the
        //  Content-Length. Such a message might indicate an attempt to perform
        //  request smuggling [...] or response splitting [...] and ought to be
        //  handled as an error." — RFC 9112 §6.3
        @Test
        void cLength_and_tEncoding()
                throws IOException, InterruptedException
        {
            // No need for a route.
            // HttpExchange creates a SkeletonRequest using RequestBody.of()
            // before routing.
            server();
            String rsp = client().writeReadTextUntilEOS("""
                GET / HTTP/1.1\r
                Transfer-Encoding: chunked\r
                Content-Length: 123\r\n\r\n""");
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 400 Bad Request\r
                Connection: close\r
                Content-Length: 0\r\n\r\n""");
            assertThat(pollServerException())
                .isExactlyInstanceOf(BadRequestException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Content-Length and Transfer-Encoding are both present.");
        }
        
        // "A client MUST NOT send content in a TRACE request."
        // — RFC 9110 §9.3.8
        @ParameterizedTest
        @ValueSource(strings = {
                "Content-Length: 1\n\nX",
                "Transfer-Encoding: chunked\n\n1\nX\n0\n\n"})
        void TRACE_hasBody(String headersAndBody)
                throws IOException, InterruptedException
        {
            server();
            var rsp = client().writeReadTextUntilEOS(
                "TRACE / HTTP/1.1\n" + headersAndBody);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 400 Bad Request\r
                Connection: close\r
                Content-Length: 0\r\n\r\n""");
            assertThat(pollServerException())
                .isExactlyInstanceOf(IllegalRequestBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Body in a TRACE request.");
        }
    }
    
    @Nested
    class ResponseValid {
        // "The server SHOULD send the same header fields in response to a HEAD
        // request as it would have sent if the request method had been GET."
        // — RFC 9110 §9.3.2
        // 
        // "A server MAY send a Content-Length header field in a response to
        //  a HEAD request" — RFC 9110 §8.6
        // 
        // TODO: This is a vanilla HEAD exchange and ought to be moved to
        //       ExampleTest, but probably enhance the API first so that we can
        //       derive a body-less response (with all headers) from a Response
        //       with a body.
        @Test
        void cLength_toHEAD() throws IOException {
            server().add("/", HEAD().apply(_ ->
                builder(TWO_HUNDRED, OK)
                    .setHeader(CONTENT_LENGTH, "123").build()));
            var rsp = client().writeReadTextUntilNewlines(
                "HEAD / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 200 OK\r
                Content-Length: 123\r\n\r\n""");
        }
        
        // "A server MAY send a Content-Length header field in a 304 (Not
        //  Modified) response to a conditional GET request" — RFC 9110 §8.6
        // 
        // Note: The implementation ResponseProcessor.ensureCorrectFraming() is
        // indifferent to which request method is being used.
        @Test
        void cLength_in304() throws IOException {
            server().add("/", GET().apply(_ ->
                builder(THREE_HUNDRED_FOUR, NOT_MODIFIED)
                    .setHeader(CONTENT_LENGTH, "123").build()));
            var rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 304 Not Modified\r
                Content-Length: 123\r\n\r\n""");
        }
        
        // "Transfer-Encoding MAY be sent in a response to a HEAD request or in
        //  a 304 (Not Modified) response to a GET request, neither of which
        //  includes a message body, to indicate that the origin server would
        //  have applied a transfer coding to the message body if the request
        //  had been an unconditional GET. This indication is not required,
        //  however, because any recipient on the response chain (including the
        //  origin server) can remove transfer codings when they are not
        //  needed." — RFC 9112 §6.1
        
        @Test
        void tEncoding_toHEAD() throws IOException {
            server().add("/", HEAD().apply(_ ->
                builder(TWO_HUNDRED, OK)
                    .setHeader(TRANSFER_ENCODING, "blah")
                    .build()));
            var rsp = client().writeReadTextUntilNewlines(
                "HEAD / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 200 OK\r
                Transfer-Encoding: blah\r\n\r\n""");
        }
        
        @Test
        void tEncoding_in304() throws IOException {
            server().add("/", GET().apply(_ ->
                builder(THREE_HUNDRED_FOUR, NOT_MODIFIED)
                    .setHeader(TRANSFER_ENCODING, "blah")
                    .build()));
            var rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 304 Not Modified\r
                Transfer-Encoding: blah\r\n\r\n""");
        }
    }
    
    ///  Asserts that the response was built just fine (not null).
    /// 
    /// I.e. the asserted problem originates from the channel-write operation.
    private static abstract class BuiltResponse {
        Response sent;
        @AfterEach void check() {
            assertNotNull(sent);
        }
    }
    
    @Nested
    class ResponseHeadersBad extends BuiltResponse {
        // "A server MUST NOT send a Transfer-Encoding header field in any
        // response with a status code of 1xx (Informational) or
        // 204 (No Content)." — RFC 9112 §6.1
        @ParameterizedTest
        @CsvSource({"123,1xx", "204,204"})
        void tEncoding_in1xx_204(int statusCode, String asStr)
                throws IOException, InterruptedException {
            server().add("/", GET().apply(_ -> {
                sent = builder(statusCode)
                    .setHeader(TRANSFER_ENCODING, "blah")
                    .build();
                // Can't return to HttpExchange, he'd whine about non-final response lol
                channel().write(sent);
                return null;
            }));
            var rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 500 Internal Server Error\r
                Content-Length: 0\r\n\r\n""");
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Transfer-Encoding header in $1 response"
                    .replace("$1", asStr));
        }
        
        // "A server MUST NOT send a Content-Length header field in any response
        //  with a status code of 1xx (Informational) or 204 (No Content)."
        //  — RFC 9110 §8.6
        @ParameterizedTest
        @CsvSource({"123,1xx", "204,204"})
        void cLength_in1xx_204(int statusCode, String asStr) throws IOException, InterruptedException {
            server().add("/", GET().apply(_ -> {
                sent = builder(statusCode)
                    .setHeader(CONTENT_LENGTH, "123")
                    .build();
                channel().write(sent);
                return null;
            }));
            var rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 500 Internal Server Error\r
                Content-Length: 0\r\n\r\n""");
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage(CONTENT_LENGTH + " header in $1 response"
                    .replace("$1", asStr));
        }
        
        // "A sender MUST NOT send a Content-Length header field in any message
        //  that contains a Transfer-Encoding header field." — RFC 9112 §6.2
        @Test
        void cLength_and_tEncoding() throws IOException, InterruptedException {
            server().add("/", GET().apply(_ -> (sent =
                builder(TWO_HUNDRED, OK).addHeaders(
                    CONTENT_LENGTH, "123",
                    TRANSFER_ENCODING, "blah").build())));
            var rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 500 Internal Server Error\r
                Content-Length: 0\r\n\r\n""");
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Both Transfer-Encoding and Content-Length headers are present.");
        }
        
        // "A server MUST NOT send any Transfer-Encoding or Content-Length
        //  header fields in a 2xx (Successful) response to CONNECT."
        //  — RFC 9110 §9.3.6
        // 
        // "A server MUST NOT send a Transfer-Encoding header field in any
        //  2xx (Successful) response to a CONNECT request." — RFC 9112 §6.1
        @ParameterizedTest
        @ValueSource(strings = {CONTENT_LENGTH, TRANSFER_ENCODING})
        void cLength_or_tEncoding_toCONNECT(String header)
                throws IOException, InterruptedException
        {
            server().add("/", RequestHandler.builder(CONNECT).apply(_ -> (sent =
                builder(TWO_HUNDRED, OK).setHeader(header, "123").build())));
            var rsp = client().writeReadTextUntilNewlines(
                "CONNECT / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 500 Internal Server Error\r
                Content-Length: 0\r\n\r\n""");
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage(header + " header in 2xx response to a CONNECT request");
        }
    }
    
    // "Any response to a HEAD request and any response with a
    //  1xx (Informational), 204 (No Content), or 304 (Not Modified) status code
    //  is always terminated by the first empty line after the header fields,
    //  regardless of the header fields present in the message, and thus cannot
    //  contain a message body or trailer section." — RFC 9112 §6.3
    //
    // "A 304 response is terminated by the end of the header section; it cannot
    //  contain content or trailers." — RFC 9110 §15.4.5
    
    @Nested
    class ResponseContentBad_FromChannel extends BuiltResponse {
        @Test
        void bodyToHEAD_knownLength() throws IOException, InterruptedException {
            server().add("/",
                HEAD().apply(_ -> (sent = text("Body!"))));
            String rsp = client().writeReadTextUntilNewlines(
                "HEAD / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 500 Internal Server Error\r
                Content-Length: 0\r\n\r\n""");
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Possibly non-empty body in response to a HEAD request.");
        }
        
        @Test
        void bodyToHEAD_unknownLength() throws IOException, InterruptedException {
            server().add("/",
                HEAD().apply(_ -> (sent = ok(ofSupplier(() -> null)))));
            String rsp = client().writeReadTextUntilNewlines(
                "HEAD / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 500 Internal Server Error\r
                Content-Length: 0\r\n\r\n""");
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Possibly non-empty body in response to a HEAD request.");
        }
        
        @Test
        void cLen_actual_discrepancy() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(_ -> (sent =
                    text("Body!").toBuilder()
                        .setHeader(CONTENT_LENGTH, "99")
                        .build())));
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 500 Internal Server Error\r
                Content-Length: 0\r\n\r\n""");
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Discrepancy between Content-Length=99 and actual body length 5");
        }
    }
    
    @Nested
    class ResponseContentBad_FromBuilder {
        Response sent;
        @AfterEach void check() {
            assertNull(sent);
        }
        
        @Test
        void bodyIn1xx_knownLength() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(_ -> (sent = Response.builder(123)
                         .body(ofString("Body!"))
                         .build())));
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1"                     + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Presumably a body in a 123 (Unknown) response.");
        }
        
        @Test
        void bodyIn1xx_unknownLength() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(_ -> (sent = Response.builder(123)
                         .body(ofSupplier(() -> null))
                         .build())));
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1"                     + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Presumably a body in a 123 (Unknown) response.");
        }
        
        @Test
        void bodyIn204_knownLength() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(_ -> (sent = noContent().toBuilder()
                         .body(ofString("Body!"))
                         .build())));
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1"                     + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Presumably a body in a 204 (No Content) response.");
        }
        
        @Test
        void bodyIn204_unknownLength() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(_ -> (sent = noContent().toBuilder()
                         .body(ofSupplier(() -> null))
                         .build())));
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1"                     + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Presumably a body in a 204 (No Content) response.");
        }
        
        @Test
        void bodyIn304_knownLength() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(_ -> (sent = builder(THREE_HUNDRED_FOUR, NOT_MODIFIED)
                         .body(ofString("Body!"))
                         .build())));
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1"                     + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Presumably a body in a 304 (Not Modified) response.");
        }
        
        @Test
        void bodyIn304_unknownLength() throws IOException, InterruptedException {
            server().add("/",
                GET().apply(_ -> (sent = builder(THREE_HUNDRED_FOUR, NOT_MODIFIED)
                         .body(ofSupplier(() -> null))
                         .build())));
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.1"                     + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Presumably a body in a 304 (Not Modified) response.");
        }
    }
}
