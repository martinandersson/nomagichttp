package alpha.nomagichttp.real;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.EndOfStreamException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.INFO;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Client-connection life-cycle tests.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ClientLifeCycleTest extends AbstractRealTest
{
    // Good src on investigating connection status
    // https://stackoverflow.com/questions/10240694/java-socket-api-how-to-tell-if-a-connection-has-been-closed/10241044#10241044
    // https://stackoverflow.com/questions/155243/why-is-it-impossible-without-attempting-i-o-to-detect-that-tcp-socket-was-grac
    
    @Test
    void http1_0_nonPersistent() throws IOException, InterruptedException {
        server().add("/", GET().respond(noContent()));
        
        Channel ch = client().openConnection();
        try (ch) {
            String rsp = client().writeRead(
                "GET / HTTP/1.0"         + CRLF +
                "Connection: keep-alive" + CRLF + CRLF); // <-- does not matter
            
            assertThat(rsp).isEqualTo(
                "HTTP/1.0 204 No Content" + CRLF +
                "Connection: close"       + CRLF + CRLF);
            
            awaitChildClose();
        }
    }
    
    // Writing to a closed channel logs ClosedChannelException
    @Test
    void serverClosesChannel_beforeResponse() throws IOException, InterruptedException {
        server().add("/", GET().accept((req, ch) -> {
            ch.closeSafe();
            ch.write(noContent());
        }));
        
        Channel ch = client().openConnection();
        try (ch) {
            String rsp = client().writeRead("GET / HTTP/1.1" + CRLF + CRLF);
            
            assertThat(rsp).isEmpty();
            
            awaitLog(
                WARNING,
                    "Child channel is closed for writing. " +
                    "Can not resolve this error. " +
                    "HTTP exchange is over.",
                ClosedChannelException.class);
            
            // Clean close from server will cause our end to close as well
            assertThat(ch.isOpen()).isFalse();
        }
        
        // <implicit assert that no error was delivered to the error handler>
    }
    
    // Client immediately closes the channel,
    // is completely ignored (no error handler and no logging).
    // See RequestHeadSubscriber.asCompletionStage()
    @Test
    void clientClosesChannel_serverReceivedNoBytes()
            throws IOException, InterruptedException, TimeoutException, ExecutionException
    {
        client().openConnection().close();
        awaitChildAccept();
        
        // Eager stop to capture all logs
        // (In reality, whole test cycle over in less than 100 ms)
        server().stop().toCompletableFuture().get(1, SECONDS);
        
        /*
         Just for the "record" (no pun intended), the log would as of 2021-03-21
         been something like this:
         
           {tstamp} | Test worker | INFO | {pkg}.DefaultServer initialize | Opened server channel: {...}
           {tstamp} | dead-25     | FINE | {pkg}.DefaultServer$OnAccept setup | Accepted child: {...}
           {tstamp} | Test worker | INFO | {pkg}.DefaultServer stopServer | Closed server channel: {...}
           {tstamp} | dead-24     | FINE | {pkg}.DefaultServer$OnAccept failed | Parent channel closed. Will accept no more children.
           {tstamp} | dead-24     | FINE | {pkg}.AnnounceToChannel$Handler completed | End of stream; other side must have closed. Will close channel's input stream.
           {tstamp} | dead-25     | FINE | {pkg}.AbstractUnicastPublisher accept | PollPublisher has a new subscriber: {...}
           {tstamp} | dead-25     | FINE | {pkg}.HttpExchange resolve | Client aborted the HTTP exchange.
           {tstamp} | dead-25     | FINE | {pkg}.DefaultChannelOperations orderlyClose | Closed child: {...}
         */
        Assertions.assertThat(stopLogRecording()
                .mapToInt(r -> r.getLevel().intValue()))
                .noneMatch(v -> v > INFO.intValue());
        
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
    void clientClosesChannel_serverReceivedSomeBytes_ignored()
            throws IOException, InterruptedException, TimeoutException, ExecutionException
    {
        client().write("XXX /incomplete");
        awaitChildAccept();
        server().stop().toCompletableFuture().get(3, SECONDS);
        
        Assertions.assertThat(stopLogRecording()
                .mapToInt(r -> r.getLevel().intValue()))
                .noneMatch(v -> v > INFO.intValue());
        
        Assertions.assertThat(pollServerError())
                .isExactlyInstanceOf(EndOfStreamException.class);
    }
    
    // TODO: Partial connection shut downs. E.g. client close his write stream,
    //       but still expects a complete response in return before server then
    //       close his write stream.
}