package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.handler.EndOfStreamException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import alpha.nomagichttp.testutil.functional.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.mediumtest.util.TestRequests.get;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Client-connection life-cycle tests.
 * 
 * @author Martin Andersson (webmaster at martinanderssonI.com)
 */
class ClientLifeCycleTest extends AbstractRealTest
{
    private static final System.Logger LOG
            = System.getLogger(ClientLifeCycleTest.class.getPackageName());
    
    // Good src on investigating connection status
    // https://stackoverflow.com/questions/10240694/java-socket-api-how-to-tell-if-a-connection-has-been-closed/10241044#10241044
    // https://stackoverflow.com/questions/155243/why-is-it-impossible-without-attempting-i-o-to-detect-that-tcp-socket-was-grac
    
    // For HTTP/1.0 clients, server will close the child
    @Test
    void http1_0_nonPersistent() throws IOException, InterruptedException {
        server().add("/", GET().apply(req -> noContent()));
        
        try (var conn = client().openConnection()) {
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.0"         + CRLF +
                "Connection: keep-alive" + CRLF + CRLF); // <-- does not matter
            
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 204 No Content" + CRLF +
                "Connection: close"       + CRLF + CRLF);
            
            assertAwaitClosingChild();
        }
    }
    
    // Writing to a closed channel logs a closed exception
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void serverClosesChannel_beforeResponse(boolean streamOnly) throws IOException, InterruptedException {
        server().add("/", GET().apply(req -> {
            if (streamOnly) {
                channel().shutdownOutput();
            } else {
                channel().close();
            }
            return noContent();
        }));
        
        try (var conn = client().openConnection()) {
            var rsp = client()
                  .writeReadTextUntilNewlines("GET / HTTP/1.1" + CRLF + CRLF);
            assertThat(rsp)
                  .isEmpty();
            logRecorder().assertAwaitRemove(
                  WARNING,
                  "Output stream is not open, can not handle this error.",
                  // This I can't really explain. lol?
                  streamOnly ?
                      AsynchronousCloseException.class :
                      ClosedChannelException.class);
            
            // Clean close from server caused our end to receive EOS
            // (it is the test worker thread that logs this message)
            logRecorder().assertAwait(
                DEBUG, "EOS; server closed my read stream.");
            if (streamOnly) {
                assertAwaitClosingChild();
            } // Else no reason for DefaultServer.handleChild to call close (again)
        }
        // Implicit assert that no error was delivered to the error handler
    }
    
    // Client immediately closes the channel; no error handler and no warning
    @Test
    void clientClosesChannel_serverReceivedNoBytes() throws IOException, InterruptedException {
        server();
        client().openConnection().close();
        logRecorder().assertAwait(
            DEBUG, "Closing the child because client aborted the exchange.");
    }
    
    /**
     * Client writes an incomplete request and then closes the channel.<p>
     * 
     * The channel reader's size is unlimited at the time of the connection
     * shutdown, and so it signals an EOS by issuing an empty buffer to the
     * downstream request-line parser, which throws a parse exception, which the
     * base handler does not log, and responds 400 (Bad Request).
     * 
     * @see ErrorTest#Special_requestBodyConsumerFails() 
     */
    @Test
    void clientClosesChannel_serverReceivedPartialHead()
            throws IOException, InterruptedException {
        server();
        client()
            .write("XXX /incomplete");
        assertThat(pollServerError())
            .hasToString("""
                RequestLineParseException{\
                prev=(hex:0x65, decimal:101, char:"e"), \
                curr=N/A, \
                pos=14, \
                msg=Upstream finished prematurely.}""")
            .hasNoCause()
            .hasNoSuppressedExceptions();
        assert400BadRequestNoWarning();
    }
    
    /**
     * A variant of the previous test, except now the upstream channel reader
     * has a known limit, and so it throws an EOS exception.<p>
     * 
     * Apart from that, the outcome is the same.
     */
    @Test
    void clientShutsDownWriteStream_serverReceivedPartialBody()
            throws IOException, InterruptedException {
        var consumed = new ArrayList<Byte>();
        var observed = new ArrayBlockingQueue<Throwable>(1);
        server().add("/", GET().apply(req -> {
            try {
                req.body().iterator().forEachRemaining(buf -> {
                    while (buf.hasRemaining()) {
                        consumed.add(buf.get());
                    }
                });
            } catch (Throwable t) {
                observed.add(t);
                throw t;
            }
            throw new AssertionError();
        }));
        try (var conn = client().openConnection()) {
            client().write(
                "GET / HTTP/1.1"    + CRLF +
                "Content-Length: 2" + CRLF + CRLF +
                
                "1");
            client().shutdownOutput();
            var thr = observed.poll(1, SECONDS);
            // We did get the byte sent, before EOS
            assertThat(consumed).containsExactly((byte) '1');
            assertThat(observed).isEmpty();
            // EOS was provided to the base handler
            assertThat(thr).isSameAs(pollServerError());
            assertThat(thr)
                .isExactlyInstanceOf(EndOfStreamException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions();
            assert400BadRequestNoWarning();
        }
    }
    
    private void assert400BadRequestNoWarning()
            throws InterruptedException, IOException {
        logRecorder().assertAwait(
                         DEBUG, "Sent 400 (Bad Request)")
                     .assertContainsOnlyOnce(
                         DEBUG, "EOS, shutting down input stream.");
        // No warnings or errors!
        stopServer();
        logRecorder().assertContainsOnlyOnce(
            DEBUG, "Saw \"Connection: close\", shutting down output.");
    }
    
    // Broken pipe ends the exchange, no error handling no logging
    @Test
    void brokenPipe() throws InterruptedException, IOException {
        // It would be weird if we could use an API to cause a broken pipe.
        // This implementation was found to work on Windows, albeit not on Linux nor macOS.
        assumeTrue(Environment.isWindows());
        server();
        try (var conn = client().openConnection()) {
            assertThatThrownBy(() ->
                client().interruptReadAfter(1, MILLISECONDS)
                        .writeReadTextUntilEOS("X"))
                .isExactlyInstanceOf(ClosedByInterruptException.class);
            // Clear flag
            Thread.interrupted();
            logRecorder().assertAwait(
                DEBUG, "Read operation failed, shutting down input stream.");
        }
        // From the test worker's TestClient
        logRecorder().assertRemove(WARNING, "About to crash");
    }
    
    // Client writes request,
    //   then shuts down output,
    //     then receives full response
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void intermittentStreamShutdown_clientOutput(
            boolean addConnCloseHeader)
            throws IOException, InterruptedException {
        var send = new Semaphore(0);
        server().add("/", GET().apply(req -> {
            send.acquire();
            return noContent();
        }));
        try (var conn = client().openConnection()) {
            client().write(addConnCloseHeader ?
                           get("Connection: close") : get())
                    .shutdownOutput();
            send.release();
            var rsp = client().readTextUntilEOS();
            assert204NoContent(rsp, addConnCloseHeader);
        }
        assertHttpExchangeCompletes(addConnCloseHeader);
    }
    
    // Client writes head,
    //   then receives response,
    //     then shuts down input,
    //       then writes the body
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void intermittentStreamShutdown_clientInput(
            boolean addConnCloseHeader)
            throws IOException, InterruptedException {
        var received = new ArrayBlockingQueue<String>(1);
        server().add("/", POST().apply(req -> {
            channel().write(noContent());
            received.add(req.body().toText());
            return null;
        }));
        try (var conn = client().openConnection()) {
            client().write(
                "POST / HTTP/1.1"   + CRLF +
                "Content-Length: 5" + CRLF);
            if (addConnCloseHeader) {
                client().write(
                    "Connection: close" + CRLF);
            }
            client().write(CRLF);
            var rsp = client().readTextUntilNewlines();
            assert204NoContent(rsp, addConnCloseHeader);
            // Done reading, but not done sending
            client().shutdownInput();
            // Server is still able to receive this:
            client().write("Body!");
            assertThat(received.poll(1, SECONDS)).isEqualTo("Body!");
        }
        assertHttpExchangeCompletes(addConnCloseHeader);
    }
    
    private static void assert204NoContent(
            String rsp, boolean requestHadConnClose) {
        assertThat(rsp).startsWith("HTTP/1.1 204 No Content" + CRLF);
        if (requestHadConnClose) {
            assertThat(rsp).endsWith("Connection: close" + CRLF + CRLF);
        }
    }
    
    private void assertHttpExchangeCompletes(boolean requestHadConnClose)
            throws InterruptedException {
        // Reason why exchange ended, is because
        logRecorder()
            .assertAwait(DEBUG, requestHadConnClose ?
                // ResponseProcessor half-closed, causing DefaultServer to close
                "Closing child: java.nio.channels.SocketChannel[connected oshut local=" :
                // or the next exchange actually started, but immediately aborted
                "Closing the child because client aborted the exchange.");
    }
    
    // Server shuts down output after response, can still read request
    @Test
    void intermittentStreamShutdown_serverOutput()
            throws IOException, InterruptedException {
        var received = new ArrayBlockingQueue<String>(1);
        server().add("/", POST().apply(req -> {
            channel().write(noContent()
                    .toBuilder()
                    .setHeader("Connection", "close")
                    .build());
            assertThat(channel().isOutputOpen())
                    .isFalse();
            // Read the request
            received.add(req.body().toText());
            return null;
        }));
        try (var conn = client().openConnection()) {
            // Until EOS!
            assertThat(client().writeReadTextUntilEOS(
                    "POST / HTTP/1.1"         + CRLF +
                    "Content-Length: 2"       + CRLF + CRLF))
                .isEqualTo(
                    "HTTP/1.1 204 No Content" + CRLF +
                    "Connection: close"       + CRLF + CRLF);
            // Client send the rest
            client().write("Hi");
            // Server proactively close the child before we do
            assertAwaitClosingChild();
        }
        assertThat(received.poll(1, SECONDS)).isEqualTo("Hi");
    }
    
    // Server shuts down input after request, can still write response
    // TODO: Also with request body the app accesses after input shutdown: -1 or AsyncCloseExc?
    @Test
    void intermittentStreamShutdown_serverInput()
            throws IOException, InterruptedException {
        server().add("/", GET().apply(req -> {
            channel().shutdownInput();
            assertThat(channel().isInputOpen()).isFalse();
            return noContent();
        }));
        assertThat(client().writeReadTextUntilEOS(
                get()))
            .isEqualTo(
                "HTTP/1.1 204 No Content" + CRLF +
                "Connection: close"       + CRLF + CRLF);
        // This asserts why
        logRecorder().assertAwait(DEBUG, """
                Setting "Connection: close" because \
                the client's input stream has shut down.""");
    }
}