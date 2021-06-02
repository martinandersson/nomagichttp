package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.message.IllegalBodyException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.Method.HEAD;
import static alpha.nomagichttp.internal.AnnounceToChannel.NO_MORE;
import static alpha.nomagichttp.internal.ChannelByteBufferPublisher.BUF_SIZE;
import static alpha.nomagichttp.internal.ResponseBodySubscriber.Result;
import static java.lang.String.join;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

/**
 * Writes a {@link Response} to the child channel.<p>
 * 
 * The response head will be written lazily, on first item published from
 * upstream or on complete. This means that an immediate error pushed from
 * upstream will give the application a chance to recover with an alternative
 * response.<p>
 * 
 * It is absolutely not anticipated that the application pushes error to the
 * <i>body</i> subscriber for something it wish to resolve through an error
 * handler. In fact, the only assumption a failed body publisher should make is
 * that "the head is probably already sent so there's no other response we can
 * produce".<p>
 * 
 * One known use-case for the lazy-head behavior, however, is an illegal body to
 * a HEAD request - which, can only reliably be identifier by this class. The
 * resulting {@code IllegalBodyException} is an exception this subscriber
 * signals through the {@link #asCompletionStage() result stage}. This indicates
 * an illegal response message variant, all of which the application should have
 * the chance to recover from.<p>
 * 
 * This class do expect to get a {@code ResponseTimeoutException} from the
 * upstream (well, hopefully not), and in fact, asynchronously (by {@link
 * TimeoutOp}). Therefore, the implementation of {@code onError()} can handle
 * calls concurrent to other signals from upstream. In addition, the timeout
 * exception will cause this class to shutdown the write stream (see {@link
 * Config#timeoutIdleConnection()}). All other signals from upstream, however,
 * must be delivered serially.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponseBodySubscriber implements SubscriberAsStage<ByteBuffer, Result>
{
    /**
     * The result of this subscriber, as a stage.
     * 
     * @see #asCompletionStage()
     */
    static final class Result {
        private final Response rsp;
        private final long len;
        
        private Result(Response rsp, long len) {
            this.rsp = rsp;
            this.len = len;
        }
        
        Response response() {
            return rsp;
        }
        
        long bytesWritten() {
            return len;
        }
    }
    
    private static final System.Logger LOG
            = System.getLogger(ResponseBodySubscriber.class.getPackageName());
    
    private static final String SP = " ", CRLF = "\r\n";
    
    // Delta kept low because server could be handling a lot of responses and
    // we don't want to keep too much garbage in memory.
    private static final int
            // Minimum bytebuffer demand.
            DEMAND_MIN = 1,
            // Maximum bytebuffer demand.
            DEMAND_MAX = 3;
    
    private final Response resp;
    private final HttpExchange exch;
    private final AnnounceToChannel sink;
    private final DefaultClientChannel chan;
    private final CompletableFuture<Result> resu;
    
    private Flow.Subscription subscription;
    private boolean pushedHead;
    private int requested;
    
    ResponseBodySubscriber(Response resp, HttpExchange exch, DefaultClientChannel ch) {
        this.resp = requireNonNull(resp);
        this.exch = requireNonNull(exch);
        this.chan = requireNonNull(ch);
        this.resu = new CompletableFuture<>();
        this.sink = AnnounceToChannel.write(ch,
                this::afterChannelFinished,
                ch.getServer().getConfig().timeoutIdleConnection());
    }
    
    /**
     * Returns the response-body-to-channel write process as a stage.<p>
     * 
     * The returned stage completes normally when the last byte has been written
     * to the channel. The result container has a reference to the response
     * written as well as a count of all bytes written.<p>
     * 
     * Errors passed down from the source publisher (the response) as well as
     * errors related to the channel write operations completes the returned
     * stage exceptionally.<p>
     * 
     * All channel related errors will cause the channel's output stream to be
     * closed, prior to completing the stage (performed by {@link
     * AnnounceToChannel}).<p>
     * 
     * Similarly, errors passed down from the source publisher will also cause
     * the channel's output stream to be closed prior to completing the stage,
     * but only if bytes have already been written to the channel (message on
     * wire is corrupt). Or in other words, an alternative response may be used
     * if the channel's output stream remains open after an exceptional
     * completion.<p>
     * 
     * @return the response-body-to-channel write process as a stage
     */
    @Override
    public CompletionStage<Result> asCompletionStage() {
        return resu;
    }
    
    @Override
    public void onSubscribe(Flow.Subscription s) {
        this.subscription = SubscriberAsStage.validate(this.subscription, s);
        s.request(requested = DEMAND_MAX);
    }
    
    @Override
    public void onNext(ByteBuffer bodyPart) {
        if (resu.isDone()) {
            subscription.cancel();
            return;
        }
        
        if (!pushedHead) {
            if (exch.getRequest().method().equalsIgnoreCase(HEAD)) {
                var e = new IllegalBodyException(
                        "Body in response to a HEAD request.", resp);
                resu.completeExceptionally(e);
                subscription.cancel();
                return;
            }
            
            pushHead();
        }
        
        if (!bodyPart.hasRemaining()) {
            LOG.log(DEBUG, "Received a ByteBuffer with no bytes remaining. Will assume we're done.");
            onComplete();
            subscription.cancel();
            return;
        }
        
        feedChannel(bodyPart);
        
        if (--requested == DEMAND_MIN) {
            requested = DEMAND_MAX;
            subscription.request(DEMAND_MAX - DEMAND_MIN);
        }
    }
    
    @Override
    public void onError(Throwable t) {
        final boolean propagates;
        if (t instanceof  ResponseTimeoutException) {
            propagates = sink.stopNow(t);
            // ...which closed only the stream. Finish the job:
            if (chan.isAnythingOpen()) {
                LOG.log(DEBUG, "Response timed out. Closing the child.");
                chan.closeSafe();
            }
        } else {
            // An application publisher hopefully never called onNext() first
            // (no clean way to "abort" the operation and save the connection)
            propagates = sink.stop(t);
        }
        
        if (!propagates) {
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
        feedChannel(NO_MORE);
    }
    
    private void pushHead() {
        final String phra = resp.reasonPhrase() == null ? "" : resp.reasonPhrase(),
                     line = exch.getHttpVersion() + SP + resp.statusCode() + SP + phra + CRLF,
                     vals = join(CRLF, resp.headersForWriting()),
                     head = line + (vals.isEmpty() ? CRLF : vals + CRLF + CRLF);
        
        ByteBuffer b = ByteBuffer.wrap(head.getBytes(US_ASCII));
        pushedHead = true;
        feedChannel(b);
    }
    
    private void afterChannelFinished(DefaultClientChannel child, long byteCount, Throwable exc) {
        if (exc == null) {
            assert byteCount > 0;
            resu.complete(new Result(resp, byteCount));
        } else {
            if (byteCount > 0 && child.isOpenForWriting()) {
                LOG.log(DEBUG, "Failed writing all of the response to channel. Will close the output stream.");
                child.shutdownOutputSafe();
            }
            subscription.cancel();
            resu.completeExceptionally(exc);
        }
    }
    
    private void feedChannel(ByteBuffer buf) {
        if (buf.remaining() <= BUF_SIZE) {
            sink.announce(buf);
        } else {
            sliceIntoChunks(buf, BUF_SIZE).forEach(sink::announce);
        }
    }
    
    private static Stream<ByteBuffer> sliceIntoChunks(ByteBuffer buff, int chunkSize) {
        Stream.Builder<ByteBuffer> b = Stream.builder();
        
        int pos = 0;
        while (pos < buff.remaining()) {
            // TODO: Use ByteBuffer.slice(index, length) in Java 13
            ByteBuffer s = buff.slice().position(pos);
            if (s.remaining() > chunkSize) {
                s.limit(pos + chunkSize);
            }
            pos += s.remaining();
            b.add(s);
        }
        
        return b.build();
    }
}