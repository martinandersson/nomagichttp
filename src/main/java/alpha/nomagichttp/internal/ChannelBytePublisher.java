package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.CompletionHandler;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

/**
 * This class publishes bytebuffers read from an asynchronous channel (assumed
 * to not support concurrent read operations).<p>
 * 
 * Many aspects of how to consume published bytebuffers has been documented in
 * {@link Request.Body} and {@link PooledByteBufferHolder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see AbstractUnicastPublisher
 */
final class ChannelBytePublisher extends AbstractUnicastPublisher<DefaultPooledByteBufferHolder>
{
    private static final System.Logger LOG = System.getLogger(ChannelBytePublisher.class.getPackageName());
    
    private static final int // TODO: Document
                             BUFF_COUNT = 5,
                             // TODO: Document (same as jdk.internal.net.http.common.Utils.DEFAULT_BUFSIZE)
                             BUFF_SIZE  = 16 * 1_024;
    
    /*
     * When a bytebuffer has been read from the channel, it will be put in a
     * queue of readable bytebuffers, from which the subscriber polls.
     * 
     * When the subscriber releases a bytebuffer, the buffer will be put in a
     * queue of writable buffers, from which channel read operations polls.
     */
    
    private final AsynchronousByteChannel channel;
    private final Deque<ByteBuffer>       readable;
    private final Queue<ByteBuffer>       writable;
    private final SeriallyRunnable        readOp;
    private final ReadHandler             handler;
    
    ChannelBytePublisher(AsynchronousByteChannel channel) {
        this.channel  = requireNonNull(channel);
        this.readable = new ConcurrentLinkedDeque<>();
        this.writable = new ConcurrentLinkedQueue<>();
        this.readOp   = new SeriallyRunnable(this::readImpl, true);
        this.handler  = new ReadHandler();
        
        IntStream.generate(() -> BUFF_SIZE)
                .limit(BUFF_COUNT)
                .mapToObj(ByteBuffer::allocateDirect)
                .forEach(writable::add);
        
        readOp.run();
    }
    
    @Override
    protected DefaultPooledByteBufferHolder poll() {
        ByteBuffer b = readable.poll();
        return b == null ? null :
                new DefaultPooledByteBufferHolder(b, (b2, read) -> release(b2));
    }
    
    private void release(ByteBuffer b) {
        if (b.hasRemaining()) {
            readable.addFirst(b);
            announce();
        } else {
            writable.add(b.clear());
            readOp.run();
        }
    }
    
    @Override
    protected void failed(DefaultPooledByteBufferHolder buffer) {
        buffer.release();
    }
    
    private void readImpl() {
        final ByteBuffer buff = writable.poll();
        
        if (buff == null) {
            // Nothing to do, complete immediately
            readOp.complete();
            return;
        }
        
        // TODO: What if this throws ShutdownChannelGroupException? Or anything else for that matter..
        channel.read(buff, buff, handler);
    }
    
    @Override
    public void close() {
        try {
            close0();
        } catch (IOException e) {
            // TODO: Deliver somewhere?
            LOG.log(ERROR, "TODO: Deliver somewhere?", e);
        }
    }
    
    private void close0() throws IOException {
        try {
            super.close();
        } finally {
            channel.close();
        }
    }
    
    private final class ReadHandler implements CompletionHandler<Integer, ByteBuffer>
    {
        @Override
        public void completed(Integer result, ByteBuffer buff) {
            final boolean announce;
            
            try {
                switch (result) {
                    case -1:
                        LOG.log(DEBUG, "End of stream; other side must have closed.");
                        close();
                        announce = false;
                        break;
                    case 0:
                        LOG.log(ERROR, "Buffer wasn't writable. Fake!");
                        // TODO: Submit AssertionError instead of logging, to subscriber, through close(Throwable)
                        close();
                        announce = false;
                        break;
                    default:
                        assert result > 0;
                        readable.add(buff.flip());
                        announce = true;
                        // 1. Schedule a new read operation
                        //    (do not announce(), see note)
                        readOp.run();
                }
            } finally {
                // 2. Complete current logical run and possibly initiate a new read operation
                readOp.complete();
            }
            
            if (announce) {
                // 3. Announce the availability to subscriber
                announce();
            }
            
            // Note: We could have announced the bytebuffer immediately after
            // putting it into the readable queue. This wouldn't have been
            // "wrong" or failed the application. Certainly, the subscriber
            // would have received the bytebuffer sooner.
            // 
            // But there is no guarantee the subscriber receiving the bytebuffer
            // won't block or otherwise take time to process it. And if he does,
            // this is time the channel could potentially be sitting around doing
            // nothing but waiting on a new read operation. So we much rather have
            // both do work in order to increase throughput.
            // 
            // Initiating a new read operation before announcing assumes then
            // that initiating is "guaranteed" to be really fast since our only
            // intention here is to increase throughput at what is conceivably
            // no significant cost for the subscriber.
            // 
            // We assume that performing this read initialization is really fast
            // for a couple of reasons:
            // 
            // 1) AsynchronousChannelGroup's JavaDoc's "threading" section writes;
            //    "the completion handler may be invoked directly by the
            //    initiating thread" only when "an I/O operation completes
            //    immediately". So, asynchronous or not, the channel implementation
            //    itself will not impose any significant delay. The question is,
            //    how would our code behave if the channel implementation call
            //    our read handler immediately on the initiating thread?
            // 
            // 2) The top-level call to readOp.complete() could effectively
            //    be stuck in a loop initiating read operations
            //    (readOp.complete() -> readOp.run() -> this.readImpl()) - but,
            //    not recursively as guaranteed by SeriallyRunnable. If the
            //    top-level's request to initiate a read operation completes
            //    immediately and the read handler is called immediately, then
            //    that second-level call to "readOp.complete()" will also return
            //    immediately and what happens then right after? announce()!
            // 
            // Conclusion: If the read operation is asynchronous - great. We
            // scheduled work for the channel and proceeded to announce, all
            // according to plan. In the "worst case", a new bytebuffer is
            // immediately available from the channel so unfortunately no "real
            // work" was scheduled but at least we will effectively proceed to
            // announce so that the subscriber is delayed no more.
            // 
            // TODO: As described in the conclusion - we only cover for the best
            // case scenario; the operation is async. But ideally, it would be
            // great if we repeatedly initiated new immediately-completed read
            // operations until we fully drained the channel of available bytes
            // and then one more read request - the async one - and then do the
            // potentially blocking announcement (repetitions limited to
            // the numbers of buffers in our pool of course, already provided
            // for in readImpl()). I believe the only thing we need to do is to
            // make sure the read handler's second-level call announce() is NOP
            // and only done at first-level - sort of a "delayed announcement".
        }
        
        @Override
        public void failed(Throwable exc, ByteBuffer buff) {
            try {
                // TODO: Deliver somewhere?
                LOG.log(ERROR, "TODO: Deliver somewhere?", exc);
                release(buff);
            } finally {
                readOp.complete();
            }
        }
    }
}