package alpha.nomagichttp.internal;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.internal.DefaultActionRegistry.Match;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.HeaderHolder;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderName.TRAILER;
import static alpha.nomagichttp.HttpConstants.HeaderName.TRANSFER_ENCODING;
import static alpha.nomagichttp.HttpConstants.Method.CONNECT;
import static alpha.nomagichttp.HttpConstants.Method.HEAD;
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.THREE_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.isClientError;
import static alpha.nomagichttp.HttpConstants.StatusCode.isServerError;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason.PROTOCOL_NOT_SUPPORTED;
import static alpha.nomagichttp.internal.Blah.CHANNEL_BLOCKING;
import static alpha.nomagichttp.internal.DefaultRequest.requestWithParams;
import static alpha.nomagichttp.internal.HttpExchange.skeletonRequest;
import static alpha.nomagichttp.util.Blah.addExactOrCap;
import static alpha.nomagichttp.util.Blah.getOrCloseResource;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static alpha.nomagichttp.util.ScopedValues.httpServer;
import static java.lang.String.join;
import static java.lang.String.valueOf;
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
 *   <li>Applies message delimiting (Transfer-Encoding or Content-Length)</li>
 *   <li>Write the response</li>
 *   <li>Manages the persistent nature of the HTTP connection</li>
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
    private final DefaultActionRegistry userActions;
    private final ChannelReader reader;
    private final ResponseProcessor libActions;
    private List<Match<AfterAction>> matches;
    private boolean dismissed;
    private long byteCount;
    private String scheduledClose;
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
        this.userActions = actions;
        this.reader = reader;
        this.libActions = new ResponseProcessor();
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
        if (scheduledClose == null) {
            scheduledClose = reason;
        }
    }
    
    @Override
    public long write(final Response app1) throws IOException {
        requireNonNull(app1);
        requireValidState();
        if (discard1XXInformational(app1) || ignoreRepeated100Continue(app1)) {
            return 0;
        }
        final Response app2 = invokeUserActions(app1);
        final Result bag = libActions.process(app2);
        try (bag) {
            return write0(bag.response());
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
    
    private static boolean discard1XXInformational(Response r) {
        if (r.isInformational() && httpVersion().isLessThan(HTTP_1_1)) {
            if (httpServer().getConfig().discardRejectedInformational()) {
                return true;
            }
            throw new ResponseRejectedException(r, PROTOCOL_NOT_SUPPORTED,
                    httpVersion() + " does not support 1XX (Informational) responses.");
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
    
    private Response invokeUserActions(Response r) {
        final var skeleton = skeletonRequest().orElse(null);
        if (skeleton == null) {
            LOG.log(DEBUG,
                "No valid request available; will not run after-actions");
            return r;
        }
        if (matches == null) {
            matches = userActions.lookupAfter(skeleton.target());
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
    
    private long write0(Response rsp) throws IOException {
        final long started;
        wroteFinal = rsp.isFinal();
        inflight = true;
        started = nanoTime();
        var it = rsp.body().iterator();
        long n = addExactOrCap(writeHead(rsp), tryWriteBody(it));
        // Request.trailers() documented close() must be called first
        it.close();
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
    
    private int writeHead(Response r) throws IOException {
        final String
            phra = requireNonNullElse(r.reasonPhrase(), ""),
            line = httpVersion() + SP + r.statusCode() + SP + phra + CRLF_STR,
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
    
    private static HttpConstants.Version httpVersion() {
        return skeletonRequest().map(SkeletonRequest::httpVersion).orElse(HTTP_1_1);
    }
    
    private record Result (
            Response response,
            ByteBufferIterator body,
            boolean closeConnection,
            boolean closeChannel) implements Closeable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }
    
    private final class ResponseProcessor
    {
        private boolean sawConnectionClose;
        
        Result process(Response app) throws IOException {
            // We call iterator and length only once, for several reasons.
            // We need a reliable length, so there must be one global iteration.
            // And a nested call to iterator could forever block if there's a
            // mutex involved (such as an exclusive file lock).
            // Improved performance is a nice bonus (e.g. length could call
            // File.size)
            // TODO: Our ChunkedEncoder should prolly be an AfterAction and app
            // actions likely also need a reliable length. In fact, everything
            // in this "processor" should become after-actions?
            var upstream = app.body();
            var it = upstream.iterator();
            return getOrCloseResource(() -> {
                long len = upstream.length();
                Response mod = closeIfOldHttp(app);
                mod = tryChunkedEncoding(mod, len, it);
                if (mod.body() != upstream) {
                    len = mod.body().length();
                    assert len == -1 : "Decorated by de-/inflating codec";
                }
                mod = trackConnectionClose(mod);
                boolean closeChannel = trackUnsuccessful(mod);
                mod = ensureCorrectFraming(mod, len);
                return new Result(
                        mod, it, mod.isFinal() && sawConnectionClose, closeChannel);
            }, it);
        }
        
        private Response closeIfOldHttp(Response r) {
            // No support for HTTP 1.0 Keep-Alive
            if (r.isFinal() &&
                httpVersion().isLessThan(HTTP_1_1) &&
                !__rspHasConnectionClose(r))
            {
                return __setConnectionClose(r);
            }
            return r;
        }
        
        private Response tryChunkedEncoding(Response r, long len, ByteBufferIterator body) {
            final boolean trailersPresent = r.headers().contains(TRAILER);
            if (httpVersion().isLessThan(HTTP_1_1)) {
                if (trailersPresent) {
                    LOG.log(DEBUG, """
                        HTTP/1.0 has no support for response trailers, \
                        discarding them""");
                    return r.toBuilder().removeTrailers().build();
                }
                return r;
            }
            if (!trailersPresent && len >= 0) {
                return r;
            }
            /*
              Instead of chunked, we could mark the end of a response by closing
              the connection. But, this is an inherently unreliable method best
              to avoid (RFC 7230 §3.3.3.).
             */
            LOG.log(DEBUG, """
                    Response trailers and/or unknown body length; \
                    applying chunked encoding""");
            if (r.headers().contains(TRANSFER_ENCODING)) {
                // TODO: Once we use more codings, implement and use
                //      Response.Builder.addHeaderToken()
                // Note; it's okay to repeat TE; RFC 7230, 3.2.2, 3.3.1.
                // But more clean to append.
                throw new IllegalArgumentException(
                    "Transfer-Encoding in response was not expected");
            }
            return r.toBuilder()
                    .addHeader(TRANSFER_ENCODING, "chunked")
                    .body(new ChunkedEncoder(body))
                    .build();
        }
        
        // TODO: ClientChannel and this implementation accepts interim Connection: close
        //       But Response.Builder forbids setting the header on an interim response!
        //       (in agreement with implicit rule from RFC 7230 §6.1)
        //       We should do no propagation. Finally, the header checks the builder
        //       do we must repeat; DRY.
        private Response trackConnectionClose(Response r) {
            final Response out;
            if (sawConnectionClose) {
                if (r.isFinal() && !__rspHasConnectionClose(r)) {
                    LOG.log(DEBUG,
                        "Connection-close flag propagates to final response");
                    out = __setConnectionClose(r);
                } else {
                    // Message not final or already has the header = NOP
                    out = r;
                }
            } else if (__rspHasConnectionClose(r)) {
                // Update flag, but no need to modify response
                sawConnectionClose = true;
                out = r;
            } else if (!r.isFinal()) {
                // Haven't seen the header before and no current state indicates we need it = NOP
                out = r;
            } else {
                final String why =
                    __reqHasConnectionClose() ? "the request headers' did." :
                    !channel().isInputOpen()  ? "the client's input stream has shut down." :
                    !httpServer().isRunning() ? "the server has stopped." :
                    scheduledClose;
                if (why != null) {
                    LOG.log(DEBUG, () ->
                        "Will set \"Connection: close\" because " + why);
                    sawConnectionClose = true;
                    out = __setConnectionClose(r);
                } else {
                    out = r;
                }
            }
            return out;
        }
        
        private boolean trackUnsuccessful(Response r) {
            // TODO: This is legacy code from before we had virtual threads when
            //       we could not rely on thread identity. Now, we might as well
            //       just use a thread-local. Probably faster.
            final String key = "alpha.nomagichttp.dcw.nUnsuccessful";
            if (isClientError(r.statusCode()) || isServerError(r.statusCode())) {
                // Bump error counter
                int n = channel().attributes().<Integer>asMapAny().merge(key, 1, Integer::sum);
                return n >= httpServer().getConfig().maxUnsuccessfulResponses();
            } else {
                // Reset
                channel().attributes().set(key, 0);
            }
            return false;
        }
        
        private static Response ensureCorrectFraming(Response r, long len) {
            if (r.headers().contains(TRANSFER_ENCODING)) {
                return __dealWithTransferEncoding(r);
            }
            assert len >= 0 : "If <= -1, chunked encoding was applied";
            final var rMethod = skeletonRequest().map(req ->
                    req.head().line().method());
            if (rMethod.filter(HEAD::equals).isPresent()) {
                return __dealWithHeadRequest(r, len);
            }
            if (r.statusCode() == THREE_HUNDRED_FOUR) {
                return __dealWith304(r, len);
            }
            final var cLength = r.headers().contentLength();
            if (cLength.isPresent()) {
                return __dealWithCL(r, rMethod, cLength.getAsLong(), len);
            } else if (len == 0) {
                return __dealWithNoCLNoBody(r, rMethod);
            } else {
                return __dealWithNoCLHasBody(r, len);
            }
        }
        
        private static boolean __rspHasConnectionClose(HeaderHolder h) {
            return h.headers().contains(CONNECTION, "close");
        }
        
        private static boolean __reqHasConnectionClose() {
            return skeletonRequest().map(SkeletonRequest::head)
                            .map(ResponseProcessor::__rspHasConnectionClose)
                            .orElse(false);
        }
        
        private static Response __setConnectionClose(Response r) {
            return r.toBuilder().header(CONNECTION, "close").build();
        }
        
        private static Response __dealWithTransferEncoding(Response r) {
            if (r.isInformational()) {
                throw new IllegalArgumentException(
                        TRANSFER_ENCODING + " header in 1xx response");
            } else if (r.statusCode() == TWO_HUNDRED_FOUR) {
                throw new IllegalArgumentException(
                        TRANSFER_ENCODING + " header in 204 response");
            }
            //  "A sender MUST NOT send a Content-Length header field in any message
            //  that contains a Transfer-Encoding header field." (RFC 7230 §3.3.2)
            if (r.headers().contentLength().isPresent()) {
                throw new IllegalArgumentException(
                        "Both $1 and $2 headers are present."
                        .replace("$1", TRANSFER_ENCODING)
                        .replace("$1", CONTENT_LENGTH));
            }
            // With transfer-encoding, framing is done
            return r;
        }
        
        private static Response __dealWithHeadRequest(Response r, long actualLen) {
            // We don't care about the presence of Content-Length, but:
            if (actualLen != 0) {
                throw new IllegalResponseBodyException(
                        "Possibly non-empty body in response to a $1 request."
                        .replace("$1", HEAD), r);
            }
            // "A server MAY send a Content-Length header field in a response to
            //  a HEAD request" (RFC 7230 §3.3.2)
            return r;
        }
        
        private static Response __dealWith304(Response r, long actualLen) {
            // "A 304 response cannot contain a message-body" (RFC 7234 §4.1)
            if (actualLen != 0) {
                throw new IllegalResponseBodyException(
                        "Possibly non-empty body in $1 response"
                        .replace("$1", valueOf(THREE_HUNDRED_FOUR)), r);
            }
            // "A server MAY send a Content-Length header field in a 304 (Not
            //  Modified) response to a conditional GET request"
            //  (RFC 7230 §3.3.2)
            return r;
        }
        
        private static final String
                BODY_IN_1XX = "Body in 1xx response",
                BODY_IN_204 = "Body in 204 response";
        
        private static Response __dealWithCL(
                Response r, Optional<String> rMethod,
                long cLength, long actualLen)
        {
                // "A server MUST NOT send a Content-Length header field in any
                //  response with a status code of 1xx (Informational) or 204
                //  (No Content)." (RFC 7230 §3.3.2)
                if (r.isInformational()) {
                    if (actualLen == 0) {
                        throw new IllegalArgumentException(
                                CONTENT_LENGTH + " header in 1xx response");
                    } else {
                        throw new IllegalResponseBodyException(
                                BODY_IN_1XX, r);
                    }
                } else if (r.statusCode() == TWO_HUNDRED_FOUR) {
                    if (actualLen == 0) {
                        throw new IllegalArgumentException(
                            CONTENT_LENGTH + " header in 204 response");
                    } else {
                        throw new IllegalResponseBodyException(
                            BODY_IN_204, r);
                    }
                } else if (r.isSuccessful()) {
                    // "A server MUST NOT send a Content-Length header field in
                    //  any 2xx (Successful) response to a CONNECT request"
                    if (rMethod.filter(CONNECT::equals).isPresent()) {
                        throw new IllegalArgumentException(
                                "$1 header in response to a $2 request"
                                .replace("$1", CONTENT_LENGTH)
                                .replace("$2", CONNECT));
                    }
                }
                if (cLength != actualLen) {
                    throw new IllegalArgumentException(
                          "Discrepancy between $1=$2 and actual body length $3"
                          .replace("$1", CONTENT_LENGTH)
                          .replace("$2", valueOf(cLength))
                          .replace("$3", valueOf(actualLen)));
                }
                // Use the given content-length
                return r;
        }
        
        private static Response __dealWithNoCLNoBody(
                Response r, Optional<String> rMethod)
        {
            return r.isInformational() ||
                   r.statusCode() == TWO_HUNDRED_FOUR ||
                   rMethod.filter(CONNECT::equals).isPresent() ?
                       r :
                       r.toBuilder().header(CONTENT_LENGTH, "0").build();
        }
        
        private static Response __dealWithNoCLHasBody(Response r, long actualLen) {
             if (r.isInformational()) {
                 throw new IllegalResponseBodyException(BODY_IN_1XX, r);
             }
             if (r.statusCode() == TWO_HUNDRED_FOUR) {
                 throw new IllegalResponseBodyException(BODY_IN_204, r);
             }
             // Would be weird if the request method is CONNECT??? (RFC 7231 §4.3.6)
            return r.toBuilder()
                .header(CONTENT_LENGTH, valueOf(actualLen))
                .build();
        }
    }
}