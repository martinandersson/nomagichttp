package alpha.nomagichttp.core;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.core.DefaultActionRegistry.Match;
import alpha.nomagichttp.core.ResponseProcessor.Result;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.FileLockTimeoutException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static alpha.nomagichttp.HttpConstants.HeaderName.TRAILER;
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.core.DefaultRequest.requestWithParams;
import static alpha.nomagichttp.core.HttpExchange.skeletonRequest;
import static alpha.nomagichttp.core.ResponseProcessor.process;
import static alpha.nomagichttp.core.VThreads.CHANNEL_BLOCKING;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason.CLIENT_PROTOCOL_DOES_NOT_SUPPORT;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason.CLIENT_PROTOCOL_UNKNOWN_BUT_NEEDED;
import static alpha.nomagichttp.util.Blah.addExactOrCap;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static alpha.nomagichttp.util.ScopedValues.httpServer;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.lang.System.nanoTime;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

/**
 * Default implementation of {@code ChannelWriter}.<p>
 * 
 * Core responsibilities:
 * <ul>
 *   <li>Invoke after-actions</li>
 *   <li>Finalize response using {@link ResponseProcessor}</li>
 *   <li>Write the response</li>
 * </ul>
 * 
 * This class closes a non-persistent HTTP connection, but it should be noted
 * that it is {@code ResponseProcessor} who makes the decision.<p>
 * 
 * The writer instance is legal to use immediately after having been created
 * until the final response has been written, or after an explicit call to
 * {@link #dismiss()}, whichever happens first.<p>
 * 
 * If this class shuts down the output stream, closes the channel, or the write
 * operation times out; then this class will also self-dismiss.<p>
 * 
 * The server must dismiss the writer after each HTTP exchange.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultChannelWriter implements ChannelWriter
{
    private static final System.Logger LOG
            = System.getLogger(DefaultChannelWriter.class.getPackageName());
    
    private static final String SP = " ", CRLF_STR = "\r\n";
    
    private final WritableByteChannel out;
    private final DefaultActionRegistry appActions;
    private final IdleConnTimeout timeout;
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
            WritableByteChannel out, DefaultActionRegistry actions, IdleConnTimeout timeout) {
        this.out = out;
        this.appActions = actions;
        this.timeout = timeout;
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
    public long write(final Response r)
            throws InterruptedException, FileLockTimeoutException, IOException {
        requireNonNull(r);
        requireValidState();
        var req = skeletonRequest().orElse(null);
        var reqVer = req == null ? null : req.httpVersion();
        var rspVer = conformantResponseVer();
        if (r.isInformational() && mayAbort(r, reqVer)) {
            return 0;
        }
        final Result bag = process(invokeAppActions(r, req), req, reqVer);
        try (bag) {
            return write0(bag.response(), bag.body(), rspVer);
        } finally {
            tryCloseAndSelfDismiss(bag);
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
    
    /**
     * {@return the HTTP version that must be used for the response}<p>
     * 
     * Currently, this method returns {@code HTTP_1_1}, whether a request has
     * been successfully parsed and whatever version the request may be
     * using.<p>
     * 
     * <a href="https://datatracker.ietf.org/doc/html/rfc9110#section-6.2">
     * RFC 9110 §6.2</a>:
     * <blockquote><pre>
     *   A server SHOULD send a response version equal to the highest version to
     *   which the server is conformant that has a major version less than or
     *   equal to the one received in the request. A server MUST NOT send a
     *   version to which it is not conformant.
     * </pre></blockquote>
     * 
     * We are not "conformant" with HTTP/0.X, nor do we yet implement HTTP/2,
     * and so returning HTTP/1.1 in all cases is just fine.<p>
     * 
     * <a href="https://datatracker.ietf.org/doc/html/rfc9112#section-2.3">
     * RFC 9112 §2.3</a>:
     * <blockquote><pre>
     *   When an HTTP/1.1 message is sent to an HTTP/1.0 recipient or a
     *   recipient whose version is unknown, the HTTP/1.1 message is constructed
     *   such that it can be interpreted as a valid HTTP/1.0 message if all the
     *   newer features are ignored.
     * </pre></blockquote>
     * 
     * This class, the {@link HttpExchange} and the {@link ResponseProcessor}
     * have logical branching that ensures no HTTP/1.1 features are sent to a
     * client not known to be at least HTTP/1.1. For example, chunked
     * encoding.<p>
     * 
     * When we add support for HTTP/2, this method implementation will have to
     * change, such that the "highest conformant version" is computed.
     * Additionally, all the aforementioned classes will likely have to be split
     * into specialized types for a given major version of HTTP, or, some other
     * mechanism will be put in place to quote unquote "shape" the response
     * message into whatever is accepted by the client.
     */
    private static Version conformantResponseVer() {
        return HTTP_1_1;
    }
    
    private boolean mayAbort(Response r, Version reqVer) {
        if (reqVer == null) {
            throw new ResponseRejectedException(
                    r, CLIENT_PROTOCOL_UNKNOWN_BUT_NEEDED, """
                    The exception handler should not be sending 1XX (Informational).""");
        }
        return discard1XXInfo(r, reqVer) || ignoreRepeated100Continue(r);
    }
    
    private static boolean discard1XXInfo(Response r, Version reqVer) {
        if (reqVer.isLessThan(HTTP_1_1)) {
            if (httpServer().getConfig().discardRejectedInformational()) {
                LOG.log(DEBUG, () ->
                    "Ignoring 1XX (Informational) response for " + reqVer + " client.");
                return true;
            }
            throw new ResponseRejectedException(r, CLIENT_PROTOCOL_DOES_NOT_SUPPORT,
                    reqVer + " client does not accept 1XX (Informational) responses.");
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
    
    private Response invokeAppActions(Response rsp, SkeletonRequest req) {
        if (req == null) {
            LOG.log(DEBUG,
                "No valid request available; will not run after-actions");
            return rsp;
        }
        if (matches == null) {
            matches = appActions.lookupAfter(req.target());
        }
        for (var m : matches) {
            final Request app = requestWithParams(req, m.segments());
            try {
                rsp = requireNonNull(m.action().apply(app, rsp));
            } catch (RuntimeException e) {
                throw new AfterActionException(e);
            }
        }
        return rsp;
    }
    
    private long write0(Response r, ByteBufferIterator body, Version ver)
            throws IOException {
        final long started;
        wroteFinal = r.isFinal();
        inflight = true;
        started = nanoTime();
        long n = addExactOrCap(writeHead(r, ver), tryWriteBody(body));
        // Request.trailers() documented that close() must be called first
        body.close();
        n = addExactOrCap(n, tryWriteTrailers(r));
        final long finished = nanoTime();
        // Revert only on success; otherwise channel is corrupt
        inflight = false;
        final var finalN = n;
        LOG.log(DEBUG, () ->
            "Sent %s (%s) {bytes: %s, duration: %s}".formatted(
                r.statusCode(), r.reasonPhrase(),
                finalN, Duration.ofNanos(finished - started)));
        httpServer().events().dispatchLazy(ResponseSent.INSTANCE,
                () -> r,
                () -> new ResponseSent.Stats(started, finished, finalN));
        return n;
    }
    
    private int writeHead(Response r, Version httpVer) throws IOException {
        final String
            phra = requireNonNullElse(r.reasonPhrase(), ""),
            line = httpVer + SP + r.statusCode() + SP + phra + CRLF_STR,
            vals = headersForWriting(r.headers()::forEach),
            head = line + vals;
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
            return r.headers().hasTransferEncodingChunked() ?
                    doWrite(asciiBytes(CRLF_STR)) :
                    0;
        }
        var tr = r.trailers();
        if (tr.isEmpty()) {
            throw new IllegalArgumentException("Empty trailers");
        }
        // TODO: Log warning if client did not indicate acceptance?
        //       (boolean accepted = request.headers().contains("TE", "trailers"))
        return doWrite(asciiBytes(headersForWriting(tr::forEach)));
    }
    
    private int doWrite(ByteBuffer buf) throws IOException {
        // In local tests, for most of the clients, the entire buffer is written
        // in the first write operation. The only exception was Apache and only
        // rarely. Wrapping the write in while() solved the problem.
        // TODO: Reactor may also have had the same prob!?
        int tot = 0;
        while (buf.hasRemaining()) {
          timeout.scheduleWrite();
          try {
              int n = out.write(buf);
              assert n > 0 : CHANNEL_BLOCKING;
              tot += n;
          } catch (Throwable t) {
              dismiss();
              assert t instanceof IOException;
              timeout.abort((IOException) t);
              // Likely already shut down, this is more for updating our state
              var ch = channel();
              if (ch.isOutputOpen()) {
                  LOG.log(DEBUG,
                      "Write operation failed, shutting down output stream.");
                  channel().shutdownOutput();
              }
              throw t;
          }
          timeout.abort(this::dismiss);
        }
        byteCount = addExactOrCap(byteCount, tot);
        return tot;
    }
    
    /**
     * Will finish with 1 {@code CRLF}, if empty, otherwise 2 {@code CRLF}.
     * 
     * @param src source
     * 
     * @return headers for writing
     */
    private static String headersForWriting(
            Consumer<BiConsumer<String, List<String>>> src) {
        var sj = new StringJoiner(CRLF_STR, "", CRLF_STR + CRLF_STR)
                .setEmptyValue(CRLF_STR);
        src.accept((k, vals) ->
                vals.forEach(v -> sj.add(k + ": " + v)));
        return sj.toString();
    }
    
    private void tryCloseAndSelfDismiss(Result bag) {
        if (bag.closeChannel()) {
            dismiss();
            var ch = channel();
            if (ch.isAnyStreamOpen()) {
                LOG.log(DEBUG, """
                        Max number of error responses reached, \
                        closing channel.""");
                ch.close();
            }
        } else if (bag.response().headers().hasConnectionClose() &&
                   channel().isOutputOpen()) {
            LOG.log(DEBUG, "Saw \"Connection: close\", shutting down output.");
            dismiss();
            channel().shutdownOutput();
        }
    }
}