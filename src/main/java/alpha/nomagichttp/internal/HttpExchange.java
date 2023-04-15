package alpha.nomagichttp.internal;

import alpha.nomagichttp.Chain;
import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.MaxRequestTrailersSizeException;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestLineParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.DummyScopedValue;

import java.io.IOException;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Optional;

import static alpha.nomagichttp.HttpConstants.HeaderName.EXPECT;
import static alpha.nomagichttp.HttpConstants.Method.TRACE;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.ClientChannel.tryAddConnectionClose;
import static alpha.nomagichttp.handler.ErrorHandler.BASE;
import static alpha.nomagichttp.internal.DefaultRequest.requestWithoutParams;
import static alpha.nomagichttp.internal.ErrorHandlerException.unchecked;
import static alpha.nomagichttp.message.Responses.continue_;
import static alpha.nomagichttp.util.DummyScopedValue.where;
import static java.lang.Integer.parseInt;
import static java.lang.Math.addExact;
import static java.lang.System.Logger.Level;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.lang.System.nanoTime;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Orchestrator of an HTTP exchange from request to response.<p>
 * 
 * Core responsibilities:
 * <ul>
 *   <li>Parse a request head</li>
 *   <li>Execute the request chain</li>
 *   <li>Resolve an exception using error handler(s)</li>
 *   <li>Write the response</li>
 *   <li>Discard remaining data in request body</li>
 * </ul>
 * 
 * Additionally:
 * <ul>
 *   <li>Respond 100 (Continue)</li>
 *   <li>Reject a TRACE request with a body</li>
 *   <li>Log the exchange workflow on DEBUG level</li>
 * </ul>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HttpExchange
{
    private static final System.Logger
            LOG = System.getLogger(HttpExchange.class.getPackageName());
    
    private static final DummyScopedValue<Optional<SkeletonRequest>>
            SKELETON_REQUEST = DummyScopedValue.newInstance();
    
    /**
     * Returns the skeleton request.<p>
     * 
     * The skeleton request is only available (non-empty Optional) after the
     * request head has been received, parsed and validated.<p>
     * 
     * If this method returns an empty Optional, then the exchange has run into
     * an early error; which does not necessarily have to be a message syntax
     * error. For example, the HTTP version could have been correctly specified,
     * just not mappable on our side to a {@link Version Version} literal, or, a
     * particular Transfer-Encoding token is not supported (we wouldn't be able
     * to read the body).
     * 
     * @return see JavaDoc
     */
    static Optional<SkeletonRequest> skeletonRequest() {
        try {
            return SKELETON_REQUEST.get();
        } catch (NoSuchElementException e) {
            return Optional.empty();
        }
    }
    
    private final HttpServer server;
    private final Config conf;
    private final Collection<ErrorHandler> handlers;
    private final ClientChannel child;
    private final ChannelReader reader;
    private final ChannelWriter writer;
    private final RequestProcessor reqProc;
    
    HttpExchange(
            HttpServer server,
            DefaultActionRegistry actions,
            DefaultRouteRegistry routes,
            Collection<ErrorHandler> handlers,
            ClientChannel child,
            ChannelReader reader,
            ChannelWriter writer)
    {
        this.server   = server;
        this.conf     = server.getConfig();
        this.handlers = handlers;
        this.child    = child;
        this.reader   = reader;
        this.writer   = writer;
        this.reqProc  = new RequestProcessor(actions, routes, reader);
    }
    
    /**
     * Begin the exchange.<p>
     * 
     * The exchange will attempt to deal with exceptions through the error
     * handler(s), and the base handler has a fallback response for all
     * exceptions. If an exception can not be processed into a response, it will
     * be logged and the channel will be set in a half-closed or fully closed
     * state. In either way, the exception is considered handled and not
     * re-thrown.<p>
     * 
     * Only a Throwable (that is not an Exception) may come out of this
     * method.<p>
     * 
     * The channel may half-close or fully close for other reasons than
     * unhandled exceptions. For example, the channel reader may reach an
     * expected end-of-stream, or the channel writer may terminate the HTTP
     * connection. This is expected and will not throw an exception.<p>
     * 
     * All things considered, the server must only begin a new HTTP exchange
     * over the same channel if this method returns normally, and both the
     * input- and output streams remain open.
     */
    void begin() {
        try {
            begin0();
        } catch (Exception e) {
            if (child.isInputOpen() && child.isOutputOpen()) {
                throw new AssertionError(e);
            }
            // Else considered handled and ignored
        }
    }
    
    void begin0() throws Exception {
        SkeletonRequest req = null;
        Response rsp;
        try {
            LOG.log(DEBUG, "Parsing the request");
            req = validate(createRequest(parseHead()));
            LOG.log(DEBUG, "Executing the request processing chain");
            rsp = process(req);
        } catch (Exception exc) {
            rsp = handleException(exc, req);
        }
        if (rsp != null) {
            assert !writer.wroteFinal();
            LOG.log(DEBUG, "Writing final response");
            if (req != null && !canDiscardRequestData(req)) {
                rsp = tryAddConnectionClose(rsp, LOG, DEBUG,
                        "can not discard remaining request data");
            }
            final var rsp2 = rsp;
            final var req2 = req;
            where(SKELETON_REQUEST, ofNullable(req), () -> {
                try {
                    writer.write(rsp2);
                } catch (Exception e) {
                    // TODO: Huge smell.
                    writer.write(handleException(e, req2));
                }
                return null;
            });
        }
        if (!child.isInputOpen() || !child.isOutputOpen()) {
            LOG.log(DEBUG, """
                    Channel is half-closed or closed, \
                    a new HTTP exchange will not begin.""");
            return;
        }
        assert writer.wroteFinal();
        if (req != null) {
            tryToDiscardRequestDataInChannel(req);
        }
        // Else we parsed a request, but it just wasn't valid/accepted.
        // This doesn't end the connection, which for instance may be upgraded.
        // TODO: This is terrible. We need to discard data from invalid request as well.
    }
    
    private RawRequest.Head parseHead() throws IOException {
        var line = new ParserOfRequestLine(
                reader, conf.maxRequestHeadSize()).parse();
        var parser = ParserOf.headers(reader, line.length(), conf.maxRequestHeadSize());
        var headers = parser.parse();
        var head = new RawRequest.Head(line, headers);
        server.events().dispatchLazy(RequestHeadReceived.INSTANCE,
                () -> head,
                () -> new RequestHeadReceived.Stats(
                        line.nanoTimeOnStart(),
                        nanoTime(),
                        addExact(line.length(), parser.byteCount())));
        return head;
    }
    
    private SkeletonRequest createRequest(RawRequest.Head h) {
        final String raw = h.line().httpVersion();
        final Version ver;
        try {
            ver = Version.parse(h.line().httpVersion());
        } catch (IllegalArgumentException e) {
            String[] comp = e.getMessage().split(":");
            if (comp.length == 1) {
                // No component for minor
                __requireHTTP1(parseInt(comp[0]), raw, HTTP_1_1, e);
                throw new AssertionError("""
                        String "HTTP/1" should have failed with \
                        parse exception (missing minor).""");
            } else {
                // No literal for major + minor (i.e., version < HTTP/0.9)
                assert comp.length == 2;
                assert parseInt(comp[0]) <= 0;
                throw new HttpVersionTooOldException(raw, HTTP_1_1, e);
            }
        }
        return new SkeletonRequest(h, ver,
                SkeletonRequestTarget.parse(h.line().target()),
                RequestBody.of(h.headers(), reader));
    }
    
    private SkeletonRequest validate(SkeletonRequest req) {
        var raw = req.head().line().httpVersion();
        var ver = req.httpVersion();
        // App's version requirement
        if (ver.isLessThan(conf.minHttpVersion())) {
            throw new HttpVersionTooOldException(raw, HTTP_1_1);
        }
        // Our version requirement
        __requireHTTP1(ver.major(), raw, HTTP_1_1, null);
        // And we also require an empty body in a TRACE request
        // (not suitable as a before-action; they are invoked only for valid requests)
        if (req.head().line().method().equals(TRACE) && !req.body().isEmpty()) {
            throw new IllegalRequestBodyException(
                    req.head(), req.body(), "Body in a TRACE request.");
        }
        return req;
    }
    
    private static void __requireHTTP1(
            int major, String raw, Version upgrade, Throwable cause) {
        if (major < 1) {
            throw new HttpVersionTooOldException(raw, upgrade, cause);
        }
        if (major > 1) { // for now
            throw new HttpVersionTooNewException(raw, cause);
        }
    }
    
    /**
     * Executes the request processor.<p>
     * 
     * The returned response is guaranteed to be in conformance with the state
     * of the writer; either null because the final response was already
     * written, or a non-null final response.
     * 
     * @param r request (assumed to not be null)
     * @return see JavaDoc
     * @throws Exception from the request processor
     */
    private Response process(SkeletonRequest r) throws Exception {
        return where(SKELETON_REQUEST, of(r), () -> {
            // Potentially send 100 (Continue)...
            if (!r.httpVersion().isLessThan(HTTP_1_1) &&
                 r.head().headers().contains(EXPECT, "100-continue"))
            {
                if (conf.immediatelyContinueExpect100()) {
                    // ...right away
                    writer.write(continue_());
                } else {
                    // ...or when app requests the body
                    r.body().onConsumption(() -> {
                        if (!writer.wroteFinal()) {
                            try {
                                writer.write(continue_());
                            } catch (Exception e) {
                                throw new AssertionError("No I/O body", e);
                            }
                        }
                    });
                }
            }
            var rsp = reqProc.execute(r);
            return __requireWriterConformance(rsp, "Request");
        });
    }
    
    private Response __requireWriterConformance(Response rsp, String entity) {
        if (rsp == null) {
            if (!writer.wroteFinal()) {
                // TODO: "processing chain"
                throw new NullPointerException(entity +
                    " processing returned null without writing a final response.");
            }
        } else if (!rsp.isFinal()) {
            throw new IllegalArgumentException(entity +
                " processing returned a non-final response.");
        } else if (writer.wroteFinal()) {
            throw new IllegalArgumentException(entity +
                " processing both wrote and returned a final response.");
        }
        return rsp;
    }
    
    /**
     * Executes the error handler chain.<p>
     * 
     * As is the case with {@link #process(SkeletonRequest)}, this method
     * also guarantees that the returned response is in conformance with the
     * state of the writer.<p>
     * 
     * If the exception can not be processed, it is re-thrown. If an error
     * handler throws an exception, it will be suppressed in favor of
     * propagating the given exception (just like Java's try-with-resources).<p>
     * 
     * Before throwing an exception, this method will ensure that the channel is
     * either in a half-closed state or fully closed. This is merely to signal
     * the enclosing server that it should not begin a new HTTP exchange.<p>
     * 
     * Before any exception leaves this method, it will first be logged.<p>
     * 
     * This method is the "end station"; either we can produce a response or
     * it'll be the end of this exchange.
     * 
     * @param e exception to handle
     * @param req the request (okay to be null)
     * 
     * @return the response produced by the error handler chain
     * 
     * @throws Exception see JavaDoc
     */
    private Response handleException(
            Exception e, SkeletonRequest req) throws Exception
    {
        if (e instanceof InterruptedException) {
            // There's no spurious interruption
            closeChannel(WARNING, "Exchange is over; thread interrupted", e);
            throw e;
        }
        if (e instanceof AfterActionException) {
            closeChannel(ERROR, "Breach of developer contract", e);
            throw e;
        }
        if (writer.byteCount() > 0) {
            closeChannel(ERROR,
                "Response bytes already sent, can not handle this error", e);
            throw e;
        }
        if (e instanceof RequestLineParseException pe && pe.byteCount() == 0) {
            closeChannel(DEBUG, "Client aborted the exchange");
            throw e;
        }
        if (!child.isOutputOpen()) {
            LOG.log(WARNING,
                "Output stream is not open, can not handle this error.", e);
            throw e;
        }
        LOG.log(DEBUG, () -> "Attempting to resolve " + e);
        return __resolve(e, req);
    }
    
    Response __resolve(Exception e, SkeletonRequest req) throws Exception {
        try {
            if (handlers.isEmpty()) {
                return BASE.apply(e, null, null);
            }
            var rsp = where(SKELETON_REQUEST,
                    req == null ? null : of(req),
                    () -> __usingHandlers(e));
            return __requireWriterConformance(rsp, "Error");
        } catch (Exception suppressed) {
            e.addSuppressed(suppressed);
            LOG.log(ERROR, "Error processing chain failed to handle this", e);
            child.close();
            throw e;
        }
    }
    
    private Response __usingHandlers(Exception e) {
        class ChainImpl extends AbstractChain<ErrorHandler> {
            ChainImpl() { super(handlers); }
            
            @Override
            Response callIntermittentHandler(
                    ErrorHandler eh, Chain passMeThrough) {
                return eh.apply(e, unchecked(passMeThrough), req());
            }
            
            @Override
            Response callFinalHandler() {
                return BASE.apply(e, null, req());
            }
            
            private Request req() {
                return skeletonRequest()
                        .map(r -> requestWithoutParams(reader, r))
                        .orElse(null);
            }
        }
        try {
            // Throws Exception but our method signature does not
            return new ChainImpl().ignite();
        } catch (ErrorHandlerException couldHappen) {
            // Breach of developer contract, i.e. UnsupportedOperationException
            throw (RuntimeException) couldHappen.getCause();
        } catch (Exception trouble) {
            throw new AssertionError(trouble);
        }
    }
    
    private static boolean canDiscardRequestData(SkeletonRequest r) {
        final long len = r.body().length();
        if (len == 0) {
            // Closing channel may be completely unnecessary, because
            // trailers may not even be there. Thank you, RFC, for telling
            // clients they "should" add the Trailer header.
            if (r.head().headers().contains("Trailer")) {
                return true;
            } // TODO: For HTTP/2, we may need another strategy here
              else return !r.head().headers().hasTransferEncodingChunked();
        }
        if (len == -1) {
            return false;
        } else return len < 666;
    }
    
    /**
     * Try to discard remaining message data in the channel.<p>
     * 
     * If discarding is not possible, the channel will be closed.
     * 
     * @param r the request
     */
    private void tryToDiscardRequestDataInChannel(SkeletonRequest r)
            throws IOException
    {
        final long len = r.body().length();
        if (len == 0) {
            if (r.head().headers().contains("Trailer")) {
                LOG.log(DEBUG, "Discarding request trailers before new exchange");
                try {
                    requestWithoutParams(reader, r).trailers();
                } catch (HeaderParseException | MaxRequestTrailersSizeException e) {
                    // DEBUG because the app was obviously not interested in the request
                    LOG.log(DEBUG, """
                        Error while discarding request trailers, \
                        shutting down the input stream.""", e);
                    child.shutdownInput();
                    throw e;
                }
            } else if (r.head().headers().hasTransferEncodingChunked()) {
                closeChannel(DEBUG, "It is unknown if trailers are present");
                return;
            }
            return;
        }
        if (len == -1) {
            closeChannel(DEBUG,
                "Unknown length of request data is remaining");
        } else if (len >= 666) {
            closeChannel(DEBUG,
                "A satanic volume of request data is remaining");
        } else {
            LOG.log(DEBUG, "Discarding request body before new exchange");
            try {
                r.body().iterator().forEachRemaining(buf ->
                        buf.position(buf.limit()));
            } catch (IOException e) {
                // DEBUG because the app was obviously not interested in the request
                LOG.log(DEBUG, "I/O error while discarding request body.", e);
                assert !child.isInputOpen();
                throw e;
            }
            assert r.body().isEmpty();
        }
    }
    
    private void closeChannel(Level level, String why) {
        if (child.isInputOpen() || child.isOutputOpen()) {
            LOG.log(level, () -> why + "; closing the channel.");
            child.close();
        }
    }
    
    private void closeChannel(Level level, String why, Exception exc) {
        if (child.isInputOpen() || child.isOutputOpen()) {
            LOG.log(level, () -> why + "; closing channel.", exc);
            child.close();
        } else {
            LOG.log(level, () -> why + ".", exc);
        }
    }
}