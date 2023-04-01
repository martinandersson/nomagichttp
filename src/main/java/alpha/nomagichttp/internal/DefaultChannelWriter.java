package alpha.nomagichttp.internal;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.internal.DefaultActionRegistry.Match;
import alpha.nomagichttp.internal.ResponseProcessor.Result;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.HttpConstants.HeaderName.TRAILER;
import static alpha.nomagichttp.HttpConstants.HeaderName.TRANSFER_ENCODING;
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason.PROTOCOL_NOT_SUPPORTED;
import static alpha.nomagichttp.internal.Blah.CHANNEL_BLOCKING;
import static alpha.nomagichttp.internal.DefaultRequest.requestWithParams;
import static alpha.nomagichttp.internal.HttpExchange.skeletonRequest;
import static alpha.nomagichttp.util.Blah.addExactOrCap;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static alpha.nomagichttp.util.ScopedValues.httpServer;
import static java.lang.String.join;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.lang.System.nanoTime;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.joining;

/**
 * Default implementation of {@code ChannelWriter}.<p>
 * 
 * Core responsibilities:
 * <ul>
 *   <li>Invoke after-actions</li>
 *   <li>Finalize response using {@link ResponseProcessor}</li>
 *   <li>Write the response</li>
 *   <li>Close a non-persistent HTTP connection</li>
 * </ul>
 * 
 * The writer instance is legal to use immediately after having been created
 * until the final response has been written, or after an explicit call to
 * {@link #dismiss()}, whichever happens first.<p>
 * 
 * If this class shuts down the output stream or closes the channel, then it
 * will also self-dismiss. The server must dismiss the writer after each HTTP
 * exchange.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Implement ResponseTimeoutException,
//       depending on design, possibly described in JavaDoc
public final class DefaultChannelWriter implements ChannelWriter
{
    private static final System.Logger LOG
            = System.getLogger(DefaultChannelWriter.class.getPackageName());
    
    private static final String SP = " ", CRLF_STR = "\r\n";
    
    private final WritableByteChannel out;
    private final DefaultActionRegistry appActions;
    private final ChannelReader reader;
    private final ResponseProcessor serverActions;
    private List<Match<AfterAction>> matches;
    private boolean dismissed;
    private long byteCount;
    // Message has begun writing, but not finished
    private boolean inflight;
    // After final response no more
    private boolean wroteFinal;
    // Counter of 100 (Continue) responses
    private int n100continue;
    
    DefaultChannelWriter(
            WritableByteChannel out,
            DefaultActionRegistry actions,
            ChannelReader reader) {
        this.out = out;
        this.appActions = actions;
        this.reader = reader;
        this.serverActions = new ResponseProcessor();
    }
    
    /**
     * Dismisses this writer.<p>
     * 
     * Dismissing a writer will cause all future calls to {@code write} to
     * throw an {@code IllegalStateException}.<p>
     * 
     * This call is NOP if the writer is already dismissed.
     */
    void dismiss() {
        this.dismissed = true;
    }
    
    @Override
    public boolean wroteFinal() {
        return wroteFinal;
    }
    
    @Override
    public long byteCount() {
        return byteCount;
    }
    
    @Override
    public void scheduleClose(String reason) {
        serverActions.scheduleClose(reason);
    }
    
    @Override
    public long write(final Response app1)
            throws InterruptedException, TimeoutException, IOException {
        requireNonNull(app1);
        requireValidState();
        var ver = skeletonRequest()
                      .map(SkeletonRequest::httpVersion)
                      .orElse(HTTP_1_1);
        if (discard1XXInformational(app1, ver) ||
            ignoreRepeated100Continue(app1)) {
            return 0;
        }
        final Response app2 = invokeAppActions(app1);
        final Result bag = serverActions.process(app2, ver);
        try (bag) {
            return write0(bag.response(), bag.body(), ver);
        } finally {
            if (bag.closeChannel()) {
                dismiss();
                var ch = channel();
                if (ch.isInputOpen() || ch.isOutputOpen()) {
                    LOG.log(DEBUG, """
                            Max number of unsuccessful responses reached, \
                            closing channel.""");
                    ch.close();
                }
            } else if (bag.closeConnection() && channel().isOutputOpen()) {
                LOG.log(DEBUG, "Saw \"Connection: close\", shutting down output.");
                dismiss();
                channel().shutdownOutput();
            }
        }
    }
    
    private void requireValidState() {
        if (dismissed) {
            throw new IllegalStateException("HTTP exchange is over");
        }
        if (inflight) {
            throw new IllegalStateException(
                "Impermissible asynchronous call or channel is corrupt");
        }
        if (wroteFinal()) {
            throw new IllegalStateException("Already wrote a final response");
        }
    }
    
    // TODO: See new log message; rename discard to ignore here and in config
    private static boolean discard1XXInformational(Response r, Version httpVer) {
        if (r.isInformational() && httpVer.isLessThan(HTTP_1_1)) {
            if (httpServer().getConfig().discardRejectedInformational()) {
                LOG.log(DEBUG,
                    "Ignoring 1XX (Informational) response for HTTP/1.0 client.");
                return true;
            }
            throw new ResponseRejectedException(r, PROTOCOL_NOT_SUPPORTED,
                    httpVer + " does not support 1XX (Informational) responses.");
        }
        return false;
    }
    
    private boolean ignoreRepeated100Continue(Response r) {
        if (r.statusCode() == ONE_HUNDRED && ++n100continue > 1) {
            LOG.log(n100continue == 2 ? DEBUG : WARNING,
                    "Ignoring repeated 100 (Continue).");
            return true;
        }
        return false;
    }
    
    private Response invokeAppActions(Response r) {
        final var skeleton = skeletonRequest().orElse(null);
        if (skeleton == null) {
            LOG.log(DEBUG,
                "No valid request available; will not run after-actions");
            return r;
        }
        if (matches == null) {
            matches = appActions.lookupAfter(skeleton.target());
        }
        for (var m : matches) {
            final Request app = requestWithParams(reader, skeleton, m.segments());
            try {
                r = requireNonNull(m.action().apply(app, r));
            } catch (RuntimeException e) {
                throw new AfterActionException(e);
            }
        }
        return r;
    }
    
    private long write0(Response rsp, ByteBufferIterator body, Version httpVer) throws IOException {
        final long started;
        wroteFinal = rsp.isFinal();
        inflight = true;
        started = nanoTime();
        long n = addExactOrCap(writeHead(rsp, httpVer), tryWriteBody(body));
        // Request.trailers() documented close() must be called first
        body.close();
        n = addExactOrCap(n, tryWriteTrailers(rsp));
        final long finished = nanoTime();
        // Revert only on success; otherwise channel is corrupt
        inflight = false;
        final var finalRsp = rsp;
        final var finalN = n;
        LOG.log(DEBUG, () ->
            "Sent %s (%s) {bytes: %s, duration: %s}".formatted(
                finalRsp.statusCode(), finalRsp.reasonPhrase(),
                finalN, Duration.ofNanos(finished - started)));
        httpServer().events().dispatchLazy(ResponseSent.INSTANCE,
                () -> finalRsp,
                () -> new ResponseSent.Stats(started, finished, finalN));
        return n;
    }
    
    private int writeHead(Response r, Version httpVer) throws IOException {
        final String
            phra = requireNonNullElse(r.reasonPhrase(), ""),
            line = httpVer + SP + r.statusCode() + SP + phra + CRLF_STR,
            vals = join(CRLF_STR, r.headersForWriting()),
            head = line + (vals.isEmpty() ? CRLF_STR : vals + CRLF_STR + CRLF_STR);
        
        // TODO: For each component, including headers, we can cache the
        //       ByteBuffers and feed the channel slices.
        return doWrite(asciiBytes(head));
    }
    
    private long tryWriteBody(ByteBufferIterator it) throws IOException {
        long n = 0;
        while (it.hasNext()) {
            var buf = it.next();
            if (buf.hasRemaining()) {
                n = addExactOrCap(n, doWrite(buf));
            } else {
                assert !it.hasNext() :
                    "This was a streaming body turned end-of-stream";
            }
        }
        return n;
    }
    
    private int tryWriteTrailers(Response r) throws IOException {
        if (!r.headers().contains(TRAILER)) {
            // This is UGLY, and will be removed soon
            // (this whole class will be refactored)
            return r.headers().contains(TRANSFER_ENCODING, "chunked") ?
                    doWrite(asciiBytes(CRLF_STR)) :
                    0;
        }
        var map = r.trailers().map();
        if (map.isEmpty()) {
            throw new IllegalArgumentException("Empty trailers");
        }
        // TODO: Log warning if client did not indicate acceptance?
        //       (boolean accepted = request.headers().contains("TE", "trailers"))
        var forWriting = map.entrySet().stream()
                .<String>mapMulti(
                    (e, sink) -> e.getValue().forEach(v ->
                        sink.accept(e.getKey() + ": " + v)))
                .collect(joining(CRLF_STR));
        return doWrite(asciiBytes(forWriting + CRLF_STR + CRLF_STR));
    }
    
    private int doWrite(ByteBuffer buf) throws IOException {
        final int n;
        try {
            n = out.write(buf);
            assert n != 0 : CHANNEL_BLOCKING;
        } catch (Throwable t) {
            dismiss();
            // Likely already shut down, this is more for updating our state
            var ch = channel();
            if (ch.isOutputOpen()) {
                LOG.log(DEBUG, "Write operation failed, shutting down output stream.");
                channel().shutdownOutput();
            }
            throw t;
        }
        byteCount = addExactOrCap(byteCount, n);
        return n;
    }
}