package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Response;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.internal.AnnounceToChannel.NO_MORE;
import static java.lang.String.join;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

/**
 * Writes a {@link Response} to the client channel.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponseBodySubscriber implements SubscriberAsStage<ByteBuffer, Long>
{
    private static final System.Logger LOG
            = System.getLogger(ResponseBodySubscriber.class.getPackageName());
    
    private static final String CRLF = "\r\n";
    
    // Delta kept low because server could be handling a lot of responses and
    // we don't want to keep too much garbage in memory.
    private static final int
            // Minimum bytebuffer demand.
            DEMAND_MIN = 1,
            // Maximum bytebuffer demand.
            DEMAND_MAX = 3;
    
    private final Response response;
    private final AnnounceToChannel channel;
    private final CompletableFuture<Long> result;
    private final DefaultServer server;
    
    private Flow.Subscription subscription;
    private boolean pushedHead;
    private int requested;
    
    ResponseBodySubscriber(Response response, AsynchronousSocketChannel channel, DefaultServer server) {
        this.response = requireNonNull(response);
        this.result   = new CompletableFuture<>();
        this.server   = requireNonNull(server);
        this.channel  = AnnounceToChannel.write(channel, server, this::whenDone);
    }
    
    /**
     * Returns the response-body-to-channel write process as a stage.<p>
     * 
     * The returned stage completes normally when the last byte has been written
     * to the channel. The result is a byte count of all bytes written.<p>
     * 
     * Errors passed down from the source publisher (the response) as well as
     * errors related to the channel write operations completes the returned
     * stage exceptionally.<p>
     * 
     * All channel related errors will cause the channel to be closed, prior to
     * completing the stage.<p>
     * 
     * Similarly, errors passed down from the source publisher will also cause
     * the channel to be closed prior to completing the stage, but only if bytes
     * have already been written to the channel (message on wire is corrupt).<p>
     * 
     * In other words, an alternative response may be used if the channel
     * remains open after an exceptional completion.
     * 
     * @return the response-body-to-channel write process as a stage
     */
    @Override
    public CompletionStage<Long> asCompletionStage() {
        return result;
    }
    
    private void whenDone(AsynchronousSocketChannel channel, long byteCount, Throwable exc) {
        if (exc == null) {
            result.complete(byteCount);
        } else {
            if (byteCount > 0) {
                LOG.log(ERROR, "Failed writing all of the response to channel. Will close the channel.", exc);
                server.orderlyShutdown(channel);
            }
            result.completeExceptionally(exc);
        }
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = SubscriberAsStage.validate(this.subscription, subscription);
        subscription.request(requested = DEMAND_MAX);
    }
    
    @Override
    public void onNext(ByteBuffer bodyPart) {
        if (result.isDone()) {
            subscription.cancel();
            return;
        }
        
        if (!pushedHead) {
            pushHead();
        }
        
        if (!bodyPart.hasRemaining()) {
            LOG.log(DEBUG, "Received a ByteBuffer with no bytes remaining. Will assume we're done.");
            onComplete();
            subscription.cancel();
            return;
        }
        
        channel.announce(bodyPart);
        
        if (--requested == DEMAND_MIN) {
            requested = DEMAND_MAX;
            subscription.request(DEMAND_MAX - DEMAND_MIN);
        }
    }
    
    @Override
    public void onError(Throwable t) {
        if (!channel.stop(t)) {
            LOG.log(WARNING, () ->
                "Response body publisher failed, but subscription was already done. " +
                "This error will be ignored.", t);
        }
    }
    
    @Override
    public void onComplete() {
        if (!pushedHead) {
            pushHead();
        }
        channel.announce(NO_MORE);
    }
    
    private void pushHead() {
        final String line = response.statusLine() + CRLF,
                     vals = join(CRLF, response.headers()),
                     head = line + (vals.isEmpty() ? CRLF : vals + CRLF + CRLF);
        
        ByteBuffer b = ByteBuffer.wrap(head.getBytes(US_ASCII));
        pushedHead = true;
        channel.announce(b);
    }
}