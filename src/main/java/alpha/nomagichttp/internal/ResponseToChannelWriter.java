package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Response;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.CompletionHandler;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;

import static java.lang.String.join;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

/**
 * Writes a {@link Response} to the client channel.<p>
 * 
 * This class is semantically the reversed version of a {@link
 * ChannelByteBufferPublisher} with the key difference being that the lifetime scope
 * of an instance of this class is much shorter; only relevant for a single
 * response.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */

// TODO: Although this is a simple design, it does create some garbage for each
//       response, specifically the "readable" and "transfer" fields. Ideally,
//       just as we do have a singleton "ChannelByteBufferPublisher" for a
//       particular channel to whom subscribers come and go over time, we would
//       similarly like to have a singleton "ChannelByteSubscriber" to whom
//       publishers come and go over time. The work to accomplish this shouldn't
//       be too grand since it's probably just a matter of redesigning the
//       life-cycle of this class.
//           Should be done when performance tests are in place so that the new
//       design can be proved to be more efficient (premature optimization is
//       the root of all evil...)

final class ResponseToChannelWriter
{
    private static final String CRLF = "\r\n";
    
    // Whenever a "rule" is referenced in source-code comments inside this file,
    // the rule should be found here (if not, this implementation is outdated):
    // https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md
    
    private static final System.Logger LOG = System.getLogger(ResponseToChannelWriter.class.getPackageName());
    
    private static final int
            // When number of outstanding requested buffers reaches this value...
            /** Minimum amount of outstanding */
            MIN_REQUESTED_BUFFERS = 1,
            // ...we request the delta to reach this value:
            // (a quite small span because the server could be handling a lot of responses)
            MAX_REQUESTED_BUFFERS = 3;
    
    /** Sentinel to stop scheduling channel write operations. */
    private static final ByteBuffer NO_MORE = ByteBuffer.allocate(0);
    
    private final AsynchronousByteChannel channel;
    private final Deque<ByteBuffer> readable;
    private final SerialTransferService<ByteBuffer> transfer;
    private final CompletableFuture<Void> result;
    
    ResponseToChannelWriter(AsynchronousByteChannel channel, Response response) {
        this.channel = requireNonNull(channel);
        
        readable = new ConcurrentLinkedDeque<>();
        transfer = new SerialTransferService<>(readable::poll, new Writer()::write);
        result   = new CompletableFuture<Void>()
                .whenComplete((ign,ored) -> { transfer.finish(); readable.clear();} );
        
        new BodySubscriber(response);
    }
    
    CompletionStage<Void> asCompletionStage() {
        return result.minimalCompletionStage();
    }
    
    private final class BodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final Response response;
        private Flow.Subscription subsc;
        private boolean pushedHead;
        private int requested;
        
        BodySubscriber(Response response) {
            this.response = response;
            
            try {
                response.body().subscribe(this);
            } catch (Throwable t) {
                if (!result.completeExceptionally(t)) {
                    LOG.log(WARNING, "subscribe() failed to return normally, but a result was already reported. This error will be ignored.", t);
                }
            }
        }
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subsc = subscription;
            // No demand exists yet, so this method will return
            subscription.request(requested = MAX_REQUESTED_BUFFERS);
        }
        
        @Override
        public void onNext(ByteBuffer body) {
            if (result.isDone()) {
                // Rule 3.1: Cancellation can only be done from within subscriber context.
                subsc.cancel();
                return;
            }
            
            if (!body.hasRemaining()) {
                LOG.log(DEBUG, "Received a ByteBuffer with no bytes remaining. Will assume we're done.");
                onComplete();
                subsc.cancel();
                return;
            }
            
            if (!pushedHead) {
                pushHead();
            }
            
            readable.add(body);
            
            if (--requested == MIN_REQUESTED_BUFFERS) {
                subsc.request(MAX_REQUESTED_BUFFERS - MIN_REQUESTED_BUFFERS);
                requested = MAX_REQUESTED_BUFFERS;
            }
            
            transfer.tryTransfer();
        }
        
        @Override
        public void onError(Throwable t) {
            if (!result.completeExceptionally(t)) {
                LOG.log(WARNING, "Response body publisher failed, but subscription was already done. This error will be ignored.", t);
            }
        }
        
        @Override
        public void onComplete() {
            if (!pushedHead) {
                pushHead();
            }
            readable.add(NO_MORE);
            transfer.tryTransfer();
        }
        
        private void pushHead() {
            final String line = response.statusLine() + CRLF,
                    vals = join(CRLF, response.headers()),
                    head = line + (vals.isEmpty() ? CRLF : vals + CRLF + CRLF);
            
            ByteBuffer buff = ByteBuffer.wrap(head.getBytes(US_ASCII));
            
            readable.add(buff);
            pushedHead = true;
            
            // Trigger first write
            transfer.increaseDemand(1);
        }
    }
    
    private final class Writer implements CompletionHandler<Integer, ByteBuffer>
    {
        void write(ByteBuffer buf) {
            if (buf == NO_MORE) {
                result.complete(null);
            } else {
                try {
                    channel.write(buf, buf, this);
                } catch (Throwable t) {
                    failed(t, null);
                }
            }
        }
        
        @Override
        public void completed(Integer ignored, ByteBuffer buf) {
            if (result.isDone()) {
                return;
            }
            
            if (buf.hasRemaining()) {
                readable.addFirst(buf);
                if (result.isDone()) {
                    readable.clear();
                    return;
                }
            }
            
            // Same as saying "I can go again" (NOP if finished)
            transfer.increaseDemand(1);
        }
        
        @Override
        public void failed(Throwable t, ByteBuffer ignored) {
            if (!result.completeExceptionally(t)) {
                LOG.log(WARNING, "Writing response body to channel failed, but subscription was already done. This error will be ignored.", t);
            }
        }
    }
}