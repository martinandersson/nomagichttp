package alpha.nomagichttp.real;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Char;
import alpha.nomagichttp.message.EndOfStreamException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.testutil.TestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.real.TestRequests.get;
import static alpha.nomagichttp.real.TestRequests.post;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestSubscribers.onNextAndError;
import static alpha.nomagichttp.util.Strings.containsIgnoreCase;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Client-connection life-cycle tests.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ClientLifeCycleTest extends AbstractRealTest
{
    private static final System.Logger LOG
            = System.getLogger(ClientLifeCycleTest.class.getPackageName());
    
    // Good src on investigating connection status
    // https://stackoverflow.com/questions/10240694/java-socket-api-how-to-tell-if-a-connection-has-been-closed/10241044#10241044
    // https://stackoverflow.com/questions/155243/why-is-it-impossible-without-attempting-i-o-to-detect-that-tcp-socket-was-grac
    
    // For HTTP/1.0, server will respond "Connection: close"
    @Test
    void http1_0_nonPersistent() throws IOException, InterruptedException {
        server().add("/", GET().respond(noContent()));
        
        Channel ch = client().openConnection();
        try (ch) {
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/1.0"         + CRLF +
                "Connection: keep-alive" + CRLF + CRLF); // <-- does not matter
            
            assertThat(rsp).isEqualTo(
                "HTTP/1.0 204 No Content" + CRLF +
                "Connection: close"       + CRLF + CRLF);
            
            awaitChildClose();
        }
    }
    
    // Writing to a closed channel logs ClosedChannelException
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void serverClosesChannel_beforeResponse(boolean streamOnly) throws IOException, InterruptedException {
        server().add("/", GET().accept((req, ch) -> {
            if (streamOnly) {
                ch.shutdownOutputSafe();
            } else {
                ch.closeSafe();
            }
            ch.write(noContent());
        }));
        
        Channel ch = client().openConnection();
        try (ch) {
            String rsp = client().writeReadTextUntilNewlines("GET / HTTP/1.1" + CRLF + CRLF);
            
            assertThat(rsp).isEmpty();
            
            awaitLog(
                WARNING,
                    "Child channel is closed for writing. " +
                    "Can not resolve this error. " +
                    "HTTP exchange is over.",
                ClosedChannelException.class);
            
            // Clean close from server caused our end (test worker) to receive EOS
            awaitLog(DEBUG, "EOS; server closed channel's read stream.");
        }
        
        // <implicit assert that no error was delivered to the error handler>
    }
    
    // Client immediately closes the channel,
    // is completely ignored (no error handler and no logging).
    // See RequestHeadSubscriber.asCompletionStage()
    @Test
    void clientClosesChannel_serverReceivedNoBytes() throws IOException, InterruptedException {
        client().openConnection().close();
        
        /*
         Just for the "record" (no pun intended), the log is as of 2021-03-21
         something like this:
         
           {tstamp} | Test worker | INFO | {pkg}.DefaultServer initialize | Opened server channel: {...}
           {tstamp} | dead-25     | FINE | {pkg}.DefaultServer$OnAccept setup | Accepted child: {...}
           {tstamp} | Test worker | INFO | {pkg}.DefaultServer stopServer | Closed server channel: {...}
           {tstamp} | dead-24     | FINE | {pkg}.DefaultServer$OnAccept failed | Parent channel closed. Will accept no more children.
           {tstamp} | dead-24     | FINE | {pkg}.AnnounceToChannel$Handler completed | End of stream; other side must have closed. Will close channel's input stream.
           {tstamp} | dead-25     | FINE | {pkg}.AbstractUnicastPublisher accept | PollPublisher has a new subscriber: {...}
           {tstamp} | dead-25     | FINE | {pkg}.HttpExchange resolve | Client aborted the HTTP exchange.
           {tstamp} | dead-25     | FINE | {pkg}.DefaultChannelOperations orderlyClose | Closed child: {...}
         */
        
        awaitChildAccept();
        assertThatNoWarningOrErrorIsLogged();
        // that no error was thrown is asserted by super class
    }
    
    /**
     * Client writes an incomplete request and then closes the channel. Error
     * handler is called, but default handler notices that the error is due to a
     * disconnect and subsequently ignores it without logging.
     * 
     * @see ErrorHandler
     */
    @Test
    void clientClosesChannel_serverReceivedPartialHead() throws IOException, InterruptedException {
        onErrorAssert(EndOfStreamException.class, channel ->
                assertThat(channel.isOpenForReading()).isFalse());
        
        client().write("XXX /incomplete");
        
        assertThat(pollServerError())
                .isExactlyInstanceOf(EndOfStreamException.class);
        
        assertThatNoWarningOrErrorIsLogged();
    }
    
    // A variant of the previous test, except EOS goes to body subscriber
    @Test
    void clientShutsDownWriteStream_serverReceivedPartialBody() throws IOException, InterruptedException {
        BlockingQueue<Throwable> appErr = new ArrayBlockingQueue<>(1);
        Semaphore shutdown = new Semaphore(0);
        server().add("/", GET().accept((req, ch) -> {
            req.body().subscribe(onNextAndError(
                    PooledByteBufferHolder::discard,
                    thr -> {
                        appErr.add(thr);
                        ch.closeSafe();
                    }));
            shutdown.release();
        }));
        Channel ch = client().openConnection();
        try (ch) {
            client().write(
                "GET / HTTP/1.1"    + CRLF +
                "Content-Length: 2" + CRLF + CRLF +
                
                "1");
            assertThat(shutdown.tryAcquire(1, SECONDS)).isTrue();
            client().shutdownOutput();
            
            assertThat(appErr.poll(1, SECONDS))
                    .isExactlyInstanceOf(EndOfStreamException.class)
                    .hasNoCause()
                    .hasNoSuppressedExceptions();
        }
        assertThatNoErrorWasLogged();
    }
    
    // Broken pipe always end the exchange, no error handling no logging
    @Test
    void brokenPipe() throws InterruptedException, IOException {
        // It would be weird if we could use an API to cause a broken pipe.
        // This implementation was found to work on Windows, albeit not on Linux.
        assumeTrue(containsIgnoreCase(System.getProperty("os.name"), "Windows"));
        
        Channel ch = client().openConnection();
        try (ch) {
            try {
                assertThatThrownBy(() ->
                        client().interruptReadAfter(1, MILLISECONDS)
                                .writeReadTextUntilEOS("X"))
                        .isExactlyInstanceOf(ClosedByInterruptException.class);
            } catch (AssertionError ae) {
                // GitHub's slow Windows Server is observing an IOException not
                // considered broken pipe. This is for debugging.
                if (!LOG.isLoggable(DEBUG)) {
                    throw ae;
                }
                var ioe = pollServerError();
                if (ioe == null) {
                    LOG.log(WARNING, "Unexpectedly, no I/O error was delivered.");
                    throw ae;
                } else if (ioe.getMessage() == null) {
                    LOG.log(WARNING, "Unexpectedly, I/O error has no message.");
                } else {
                    var msg = ioe.getMessage();
                    LOG.log(DEBUG, "Message of I/O error: \"" + msg + "\".");
                    LOG.log(DEBUG, "Will dump details on the last five chars.");
                    int cap = Math.min(msg.length(), 5);
                    msg.substring(msg.length() - cap).chars().forEach(c -> {
                        LOG.log(DEBUG, Char.toDebugString((char) c));
                    });
                    ae.addSuppressed(ioe);
                }
                throw ae;
            }
            
            Thread.interrupted(); // Clear flag
            awaitLog(DEBUG, "Read operation failed (broken pipe), will shutdown stream.");
            // <here>, log may be different, see next comment
            awaitLog(DEBUG, "Broken pipe, closing channel. (end of HTTP exchange)");
        }
        
        // What can be said about the log before the end of the exchange is
        // dependent on the speed of the machine.
        // 
        // On my Windows 10 machine (fast), a RequestHeadSubscriber subscribes
        // to the channel and consequently the HttpExchange will observe the
        // broken pipe and ignore it, well, except for closing the child of
        // course. In this case, the log will only indicate a broken read. No
        // other errors are logged.
        // 
        // On GitHub's Windows Server 2019 (slow), the broken pipe will happen
        // before the head subscriber arrives. In regards to the log, this error
        // is still ignored by both ChannelByteBufferPublisher and
        // AnnounceToChannel; no stack trace logged.
        //     However, when the head subscriber do arrive, he will be
        // terminated with an "IllegalStateException: Publisher has shutdown.",
        // which is delivered to the error handler, which logs the exception
        // with stack and attempts to respond 500 Internal Server Error, which
        // then hits a broken pipe on the write operation.
        
        // So, this may happen:
        Throwable thr = pollServerErrorNow();
        if (thr != null) {
            assertThat(thr)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Publisher has shutdown.");
            
            // And if it does, we also expect the write failure
            awaitLog(DEBUG, "Write operation failed (broken pipe), will shutdown stream.");
        }
        
        // Test client will have logged a WARNING, "about to crash".
        // And of course, error handler might have logged the closed publisher.
        // Both we allow.
        assertThatNoWarningOrErrorIsLoggedExcept(TestClient.class, ErrorHandler.class);
    }
    
    // Client shuts down output stream after request, still receives the full response
    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void intermittentStreamShutdown_clientOutput(
            boolean setConnectionCloseHeader, boolean useRequestBody)
            throws IOException, InterruptedException
    {
        var resp  = new CompletableFuture<Response>();
        var meth = useRequestBody ? RequestHandler.POST() : GET();
        server().add("/", meth.respond(resp));
        Channel ch = client().openConnection();
        try (ch) {
            String[] conn = setConnectionCloseHeader ?
                    new String[]{"Connection: close"} :
                    new String[0];
            String req = useRequestBody ?
                    post("body", conn) :
                    get(conn);
            client().write(req);
            client().shutdownOutput();
            resp.complete(noContent());
            String rsp = client().readTextUntilEOS();
            
            if (setConnectionCloseHeader) {
                assertThat(rsp).isEqualTo(
                    "HTTP/1.1 204 No Content" + CRLF +
                    "Connection: close"       + CRLF + CRLF);
            } else {
                // No guarantee the server observes the half-close before response,
                // so no guarantee the close header has been set in response.
                assertThat(rsp).startsWith(
                    "HTTP/1.1 204 No Content" + CRLF);
            }
            
            // Sooner or later the half-close will be observed
            awaitChildClose();
        }
        
        // We can't say exactly how the HTTP exchange ends,
        // except it's going to end in one of three ways:
        assertTrue(logRecorder().await(rec ->
            // 1) Client's shutdown may be observed...
            "Input stream was shut down. HTTP exchange is over.".equals(rec.getMessage()) ||
            // 2) before the close-header, or if none of these, ...
            "Request set \"Connection: close\", shutting down input.".equals(rec.getMessage()) ||
            // 3) the next logical exchange will immediately abort.
            "Client aborted the HTTP exchange.".equals(rec.getMessage())));
    }
    
    // Client receives response first, then shuts down input stream, then server get the request
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void intermittentStreamShutdown_clientInput(boolean setConnectionCloseHeader) throws IOException, InterruptedException {
        BlockingQueue<String> received = new ArrayBlockingQueue<>(1);
        server().add("/", POST().accept((req, ch) -> {
            ch.write(noContent());
            req.body().toText().thenAccept(received::add);
        }));
        Channel ch = client().openConnection();
        try (ch) {
            client().write(
                "POST / HTTP/1.1"   + CRLF +
                "Content-Length: 5" + CRLF);
            if (setConnectionCloseHeader) {
                client().write(
                    "Connection: close" + CRLF + CRLF);
            } else {
                client().write(CRLF);
            }
            
            String rsp = client().readTextUntilNewlines();
            if (setConnectionCloseHeader) {
                assertThat(rsp).isEqualTo(
                    "HTTP/1.1 204 No Content" + CRLF +
                    "Connection: close"       + CRLF + CRLF);
            } else {
                assertThat(rsp).startsWith(
                    "HTTP/1.1 204 No Content" + CRLF);
            }
            
            // Done reading, but not done sending
            client().shutdownInput();
            // Server is still able to receive this:
            client().write("Body!");
            
            assertThat(received.poll(1, SECONDS)).isEqualTo("Body!");
            
            // Half-closed is NOT observed by server
            // (coz there's no outstanding channel operation after having sent the response)
            if (setConnectionCloseHeader) {
                awaitChildClose();
            }
        }
        
        if (setConnectionCloseHeader) {
            awaitLog(DEBUG, "Normal end of HTTP exchange.");
        } else {
            // As with the previous test, no guarantee how (when) exactly the exchange ends
            assertTrue(logRecorder().await(rec ->
              "Input stream was shut down. HTTP exchange is over.".equals(rec.getMessage()) ||
              "Normal end of HTTP exchange.".equals(rec.getMessage())));
        }
    }
    
    // Server shuts down output after response, can still read request
    @Test
    void intermittentStreamShutdown_serverOutput() throws IOException, InterruptedException {
        BlockingQueue<String> received = new ArrayBlockingQueue<>(1);
        server().add("/", POST().accept((req, ch) -> {
            req.body().toText().thenAccept(received::add);
            ch.write(noContent()
                    .toBuilder()
                    .mustShutdownOutputAfterWrite(true)
                    .build());
        }));
        Channel ch = client().openConnection();
        try (ch) {
            assertThat(client().writeReadTextUntilEOS(
                    "POST / HTTP/1.1"         + CRLF +
                    "Content-Length: 2"       + CRLF + CRLF))
                .isEqualTo(
                    "HTTP/1.1 204 No Content" + CRLF +
                    "Connection: close"       + CRLF + CRLF);
            
            // Send the rest of the request
            client().write("Hi");
            awaitChildClose();
        }
        
        assertThat(received.poll(1, SECONDS)).isEqualTo("Hi");
    }
    
    // Server shuts down input after request, can still write response
    @Test
    void intermittentStreamShutdown_serverInput() throws IOException {
        server().add("/", GET().accept((req, ch) -> {
            ch.shutdownInputSafe();
            ch.write(noContent());
        }));
        assertThat(client().writeReadTextUntilEOS(
                get()))
            .isEqualTo(
                "HTTP/1.1 204 No Content" + CRLF +
                "Connection: close"       + CRLF + CRLF);
    }
}