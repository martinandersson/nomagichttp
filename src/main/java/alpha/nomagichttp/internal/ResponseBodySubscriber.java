package alpha.nomagichttp.internal;

import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.util.Headers;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.HeaderName.TRANSFER_ENCODING;
import static alpha.nomagichttp.HttpConstants.Method.CONNECT;
import static alpha.nomagichttp.HttpConstants.Method.HEAD;
import static alpha.nomagichttp.internal.AnnounceToChannel.NO_MORE;
import static alpha.nomagichttp.internal.ChannelByteBufferPublisher.BUF_SIZE;
import static java.lang.String.join;
import static java.lang.System.Logger;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.lang.System.getLogger;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.joining;

/**
 * Writes a {@link Response} to the child channel and emits {@link
 * ResponseSent}.<p>
 * 
 * All the response will be written by this class; head, body and trailers.<p>
 * 
 * The response head will be written lazily, on first item published from
 * upstream or on complete. This means that an error pushed from upstream before
 * the body bytes will give the application a chance to recover with an
 * alternative response.<p>
 * 
 * It is absolutely not anticipated that the application pushes an error to the
 * <i>body</i> subscriber for something it wishes to resolve through an error
 * handler. The only assumption a failed body publisher should make is that "the
 * head or pieces of it is probably already sent so there's no other response we
 * can produce".<p>
 * 
 * One known use-case for the lazy-head behavior, however, is an illegal body to
 * a HEAD/CONNECT request - which, can only reliably be identifier by this
 * class. The resulting {@code IllegalResponseBodyException} completes
 * exceptionally the {@link #result() result stage}.<p>
 * 
 * This class do expect to get a {@link ResponseTimeoutException} from the
 * upstream (well, hopefully not); asynchronously by {@link TimeoutOp}.
 * Therefore, the implementation of {@code onError()} can handle concurrent
 * calls. The timeout exception will cause this class to close the channel. All
 * other signals from upstream must be delivered serially.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponseBodySubscriber
        implements SubscriberWithResult<ByteBuffer, Long>
{
    private static final Logger LOG
            = getLogger(ResponseBodySubscriber.class.getPackageName());
    
    private static final String SP = " ", CRLF_STR = "\r\n";
    
    // Delta kept low because server could be handling a lot of responses and
    // we don't want to keep too much garbage in memory.
    private static final int
            // Minimum bytebuffer demand which will trigger a new request.
            DEMAND_MIN = 2,
            // Initial and maximum bytebuffer demand.
            DEMAND_MAX = 4;
    
    private final Response resp;
    private final HttpExchange exch;
    private final AnnounceToChannel sink;
    private final DefaultClientChannel chApi;
    private final CompletableFuture<Long> resu;
    
    private Flow.Subscription subscription;
    private int requested;
    private boolean pushedHead;
    private long started;
    
    ResponseBodySubscriber(Response resp, HttpExchange exch, DefaultClientChannel chApi) {
        this.resp  = requireNonNull(resp);
        this.exch  = requireNonNull(exch);
        this.chApi = chApi;
        this.resu  = new CompletableFuture<>();
        this.sink  = AnnounceToChannel.write(chApi,
                this::afterChannelFinished);
    }
    
    /**
     * Returns the response-body-to-channel write process as a stage.<p>
     * 
     * The returned stage completes normally when the last byte has been written
     * to the channel. The result will be a count of all bytes written.<p>
     * 
     * Errors passed down from the upstream (the response body publisher or
     * trailers stage) as well as errors related to the channel write operations
     * completes the returned stage exceptionally.<p>
     * 
     * All channel related errors will cause the channel's output stream to be
     * closed, prior to completing the stage (performed by {@link
     * AnnounceToChannel}).<p>
     * 
     * Similarly, errors passed down from the upstream will also cause the
     * channel's output stream to be closed prior to completing the stage, but
     * only if bytes have already been written to the channel (message on wire
     * is corrupt). Or in other words, an alternative response may be used if
     * the channel's output stream remains open after an exceptional completion.
     * 
     * @return the response-body-to-channel write process as a stage
     */
    @Override
    public CompletionStage<Long> result() {
        return resu;
    }
    
    @Override
    public void onSubscribe(Flow.Subscription s) {
        this.subscription = SubscriberWithResult.validate(this.subscription, s);
    }
    
    /**
     * Request initial demand.<p>
     * 
     * An external start enables the response pipeline to switch over from the
     * high-level timer driven by the pipeline itself to the publisher timer
     * only after the publisher's {@code subscribe} method has returned, which
     * is untrusted and theoretically blocking application code.
     * 
     * @return {@code this} for chaining/fluency
     */
    ResponseBodySubscriber start() {
        // This too may be blocking, but TimeoutOp.Pub will start the timer
        // before yielding to the application code (see fromDownstreamRequest)
        subscription.request(requested = DEMAND_MAX);
        return this;
    }
    
    private void pushHead() {
        final String phra = resp.reasonPhrase() == null ? "" : resp.reasonPhrase(),
                     line = exch.getHttpVersion() + SP + resp.statusCode() + SP + phra + CRLF_STR,
                     vals = join(CRLF_STR, resp.headersForWriting()),
                     head = line + (vals.isEmpty() ? CRLF_STR : vals + CRLF_STR + CRLF_STR);
        
        // TODO: For each component, including headers, we can cache the
        //       ByteBuffers and feed the channel slices.
        ByteBuffer b = wrap(head.getBytes(US_ASCII));
        pushedHead = true;
        started = System.nanoTime();
        feedChannel(b);
    }
    
    @Override
    public void onNext(ByteBuffer bodyPart) {
        if (resu.isDone()) {
            subscription.cancel();
            return;
        }
        
        if (!pushedHead) {
            var reqHead = exch.getRequestHead();
            var reqMethod = reqHead == null ? null : reqHead.line().method();
            if (reqMethod != null && (reqMethod.equals(HEAD) || reqMethod.equals(CONNECT))) {
                var e = new IllegalResponseBodyException(
                        "Body in response to a " + reqMethod + " request.", resp);
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
        onError0(t, "body publisher");
    }
    
    private void onError0(Throwable t, String origin) {
        final boolean propagates;
        if (t instanceof  ResponseTimeoutException) {
            propagates = sink.stopNow(t);
            // ...which closed only the stream. Finish the job:
            if (chApi.isAnythingOpen()) {
                LOG.log(DEBUG, () ->
                    "Response %s timed out. Closing the child.".formatted(origin));
                chApi.closeSafe();
            }
        } else {
            // An application publisher hopefully never called onNext() first
            // (no clean way to "abort" the operation and save the connection)
            propagates = sink.stop(t);
        }
        if (!propagates) {
            Supplier<String> msg = () -> """
                Response %s failed, but subscription was already done. \
                This error will be ignored.
                """.formatted(origin);
            LOG.log(WARNING, msg, t);
        }
    }
    
    private static final byte[] CRLF_BYTES = {13, 10};
    
    @Override
    public void onComplete() {
        if (!pushedHead) {
            pushHead();
        }
        var tr = resp.trailers();
        if (tr.isPresent()) {
            pushTrailers(tr.get());
        } else {
            // We have to be very fast here, so a few assumptions are made
            //   1) only 1 Transfer-Encoding line
            //   2) the value is lower cased
            // TODO: ResponsePipeline must specify and enforce the Transfer-Encoding structure
            resp.headers().delegate()
                    .firstValue(TRANSFER_ENCODING)
                    .filter(v -> v.endsWith("chunked"))
                    // ChunkedEncoderOp only encodes body bytes
                    .ifPresent(ignored -> feedChannel(wrap(CRLF_BYTES)));
            feedChannel(NO_MORE);
        }
    }
    
    private void pushTrailers(CompletionStage<HttpHeaders> trailers) {
        // java.util.concurrent.CancellationException
        CompletableFuture<HttpHeaders> fut = null;
        try {
            fut = trailers.toCompletableFuture();
        } catch (UnsupportedOperationException t) {
            LOG.log(WARNING, """
                Response trailers stage does not support toCompletableFuture(). \
                Can not schedule a timeout.
                """);
        }
        if (fut != null) {
            var dur = chApi.getServer().getConfig().timeoutResponse();
            trailers = fut.orTimeout(dur.toNanos(), NANOSECONDS);
        }
        trailers.whenComplete((tr, thr) -> {
            if (thr instanceof TimeoutException) {
                chApi.scheduleClose("Response trailers timed out");
                onError0(new ResponseTimeoutException(
                     "Gave up waiting on response trailers."), "trailers");
                return;
            } else if (thr instanceof CancellationException) {
                tr = Headers.of();
            } else if (thr != null) {
                onError0(thr, "trailers");
                return;
            }
            var forWriting = tr.map().entrySet().stream()
                .<String>mapMulti(
                    (e, sink) -> e.getValue().forEach(v ->
                        sink.accept(e.getKey() + ": " + v)))
                .collect(joining(CRLF_STR));
            if (!forWriting.isEmpty()) {
                feedChannel(wrap((forWriting + CRLF_STR).getBytes(US_ASCII)));
            }
            feedChannel(wrap(CRLF_BYTES));
            feedChannel(NO_MORE);
        });
    }
    
    private void feedChannel(ByteBuffer buf) {
        if (buf.remaining() <= BUF_SIZE) {
            sink.announce(buf);
        } else {
            sliceIntoChunks(buf, BUF_SIZE)
                  .forEach(sink::announce);
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
    
    private void afterChannelFinished(long byteCount, Throwable exc) {
        if (exc == null) {
            assert byteCount > 0;
            chApi.getServer().events().dispatchLazy(ResponseSent.INSTANCE, () -> resp, () ->
                    new ResponseSent.Stats(started, System.nanoTime(), byteCount));
            resu.complete(byteCount);
        } else {
            if (byteCount > 0 && chApi.isOpenForWriting()) {
                LOG.log(DEBUG, "Failed writing all of the response to channel. Will close the output stream.");
                chApi.shutdownOutputSafe();
            }
            subscription.cancel();
            resu.completeExceptionally(exc);
        }
    }
}