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
import java.nio.channels.ClosedByInterruptException;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Optional;

import static alpha.nomagichttp.HttpConstants.HeaderName.EXPECT;
import static alpha.nomagichttp.HttpConstants.Method.TRACE;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.ClientChannel.tryAddConnectionClose;
import static alpha.nomagichttp.handler.ErrorHandler.BASE;
import static alpha.nomagichttp.internal.ErrorHandlerException.unchecked;
import static alpha.nomagichttp.message.Responses.continue_;
import static alpha.nomagichttp.util.Blah.throwsNoChecked;
import static alpha.nomagichttp.util.DummyScopedValue.where;
import static java.lang.Integer.parseInt;
import static java.lang.Math.addExact;
import static java.lang.System.Logger.Level;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.lang.System.nanoTime;
import static java.util.Optional.empty;
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
 *   <li>Possibly discard remaining data in the request body</li>
 * </ul>
 * 
 * Additionally:
 * <ul>
 *   <li>Respond 100 (Continue)</li>
 *   <li>Reject a TRACE request with a body</li>
 *   <li>Log the exchange workflow on DEBUG level</li>
 * </ul>
 * 
 * This class also implicitly manages the child's life-cycle, in that the
 * child will remain fully open for as long as the enclosing server should keep
 * running new exchanges. This responsibility is implemented in cooperation
 * with {@link ChannelReader}, who half-closes the child on end-of-stream, and
 * with {@link DefaultChannelWriter}, who bears the brunt of the burden to
 * half-close or close the child if the connection is deemed not persistent.
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
            return empty();
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
        this.reqProc  = new RequestProcessor(actions, routes);
    }
    
    /**
     * Begin the exchange.<p>
     * 
     * The exchange will attempt to deal with exceptions through the error
     * handler(s), and the base handler has a fallback response for all
     * exceptions. If an exception can not be processed into a response and
     * transmitted, the exception or a new one (from failed write) will be
     * logged, and the channel will be set in a half-closed or fully closed
     * state. The exception is considered handled and not re-thrown.<p>
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
        final SkeletonRequest req;
        try {
            LOG.log(DEBUG, "Parsing the request");
            req = validate(createRequest(parseHead()));
        } catch (Exception e) {
            handleException(e);
            return;
        }
        handleRequest(req);
        if (!child.isInputOpen() || !child.isOutputOpen()) {
            LOG.log(DEBUG, """
                    Channel is half-closed or closed, \
                    a new HTTP exchange will not begin.""");
            return;
        }
        assert writer.wroteFinal();
        tryDiscardRequest(req, false)
            .ifPresent(this::closeChannel);
    }
    
    private RawRequest.Head parseHead() throws IOException {
        var line = new ParserOfRequestLine(
                reader, conf.maxRequestHeadSize()).parse();
        var parser = ParserOf.headers(
                reader, line.length(), conf.maxRequestHeadSize());
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
    
    private SkeletonRequest createRequest(RawRequest.Head head) {
        final String raw = head.line().httpVersion();
        final Version ver;
        try {
            ver = Version.parse(head.line().httpVersion());
        } catch (IllegalArgumentException e) {
            String[] comp = e.getMessage().split(":");
            if (comp.length == 1) {
                // No component for minor
                requireHTTP1(parseInt(comp[0]), raw, HTTP_1_1, e);
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
        return new SkeletonRequest(
                head, ver,
                SkeletonRequestTarget.parse(head.line().target()),
                RequestBody.of(head.headers(), reader),
                reader);
    }
    
    private SkeletonRequest validate(SkeletonRequest req) {
        var raw = req.head().line().httpVersion();
        var ver = req.httpVersion();
        // App's version requirement
        if (ver.isLessThan(conf.minHttpVersion())) {
            throw new HttpVersionTooOldException(raw, HTTP_1_1);
        }
        // Our version requirement
        requireHTTP1(ver.major(), raw, HTTP_1_1, null);
        // And we also require an empty body in a TRACE request
        // (not suitable as a before-action; they are invoked only for valid requests)
        if (req.head().line().method().equals(TRACE) && !req.body().isEmpty()) {
            throw new IllegalRequestBodyException(
                    req.head(), req.body(), "Body in a TRACE request.");
        }
        return req;
    }
    
    private static void requireHTTP1(
            int major, String raw, Version upgrade, Throwable cause) {
        if (major < 1) {
            throw new HttpVersionTooOldException(raw, upgrade, cause);
        }
        if (major > 1) { // for now
            throw new HttpVersionTooNewException(raw, cause);
        }
    }
    
    private void handleRequest(SkeletonRequest req) throws Exception {
        where(SKELETON_REQUEST, of(req), () -> {
          try {
              LOG.log(DEBUG, "Executing the request processing chain");
              var rsp = processRequest(req);
              if (rsp.isPresent()) {
                  // If the request isn't discarded later, add "Connection: close" now
                  var r = tryDiscardRequest(req, true).map(whyNot ->
                            tryAddConnectionClose(rsp.get(), LOG, DEBUG, whyNot))
                          .orElseGet(rsp::get);
                  LOG.log(DEBUG, "Writing final response");
                  writer.write(r);
              }
          } catch (Exception e) {
              handleException(e);
          }
          return null;
      });
    }
    
    /**
     * Executes the request processor.<p>
     * 
     * The returned response is guaranteed to be in conformance with the state
     * of the writer; either an empty {@code Optional} because a final response
     * was already written, or a final response contained within the returned
     * {@code Optional}.
     * 
     * @param req the request
     * 
     * @return see JavaDoc
     * 
     * @throws Exception
     *             from the request processor
     */
    private Optional<Response> processRequest(SkeletonRequest req) throws Exception {
        // Potentially send 100 (Continue)...
        if (!req.httpVersion().isLessThan(HTTP_1_1) &&
             req.head().headers().contains(EXPECT, "100-continue"))
        {
            if (conf.immediatelyContinueExpect100()) {
                // ...right away
                writer.write(continue_());
            } else {
                // ...or when app requests the body
                req.body().onConsumption(() -> {
                    if (!writer.wroteFinal()) {
                        // Exception not expected, at this point
                        throwsNoChecked(() -> writer.write(continue_()));
                    }
                });
            }
        }
        var rsp = reqProc.execute(req);
        return requireWriterConformance(rsp, "Request");
    }
    
    private Optional<Response> requireWriterConformance(Response rsp, String entity) {
        if (rsp == null) {
            if (!writer.wroteFinal()) {
                throw new NullPointerException(entity + """
                        processing chain returned null \
                        without writing a final response.""");
            }
        } else if (!rsp.isFinal()) {
            throw new IllegalArgumentException(entity +
                " processing chain returned a non-final response.");
        } else if (writer.wroteFinal()) {
            throw new IllegalArgumentException(entity +
                " processing chain both wrote and returned a final response.");
        }
        return ofNullable(rsp);
    }
    
    /**
     * Calls {@link #tryProcessException(Exception)} and writes the fallback
     * response to the channel.<p>
     * 
     * If writing the fallback response fails, the new exception is logged, then
     * the channel will be closed, and then the new exception is thrown.<p>
     * 
     * It should be noted that which exception this method throws is irrelevant.
     * This method is the "end station"; either we can produce and write a
     * fallback response or it'll be the end of the exchange.
     * 
     * @param e the exception to handle
     * 
     * @throws Exception
     *             from failure to process the exception or write the response
     */
    private void handleException(Exception e) throws Exception {
        var rsp = tryProcessException(e);
        if (rsp.isPresent()) {
            try {
                writer.write(rsp.get());
            } catch (Exception fromWrite) {
                closeChannel(ERROR,
                    "Failed to write fallback response", fromWrite);
                throw fromWrite;
            }
        }
    }
    
    /**
     * Executes the error handler chain.<p>
     * 
     * As is the case with {@link #processRequest(SkeletonRequest)}, this method
     * also guarantees that the returned response is in conformance with the
     * state of the writer.<p>
     * 
     * If this method does not process the exception or processing fails, the
     * given exception is logged, then the channel will be closed, and then the
     * given exception is rethrown.
     * 
     * @param e the exception to process
     * 
     * @return the response produced by the error handler chain
     * 
     * @throws Exception
     *             see JavaDoc
     */
    private Optional<Response> tryProcessException(Exception e) throws Exception {
        if (e instanceof InterruptedException) {
            // There's no spurious interruption
            closeChannel("thread interrupted");
            throw e;
        }
        // TODO: Currently we are assuming this comes from our channel. Mark
        //       this exception; I/O errors from app code we must try to resolve
        //       lol. Also update code comment in DefaultClientChannel.
        if (e instanceof ClosedByInterruptException) {
            if (!server.isRunning()) {
                // This we expect; all servers stop at some point lol
                closeChannel("the application stopped the server");
            } else {
                // Only logged if either stream is open; should never happen
                closeChannel(WARNING,
                      "the ClientChannel API was not used to close the child");
            }
            throw e;
        }
        // Most likely, the client closed his output (reader EOS)
        if (e instanceof RequestLineParseException pe && pe.byteCount() == 0) {
            closeChannel("client aborted the exchange");
            throw e;
            // TODO: We may want to raise events for these aborts,
            //       1) Clean abort with no previous communication
            //          = suspicious!
            //       2) We have a previous request
            //          = bad client software did not set "Connection: close"
            //            (we save and report the user-agent)
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
        if (!child.isOutputOpen()) {
            LOG.log(WARNING,
                "Output stream is not open, can not handle this error.", e);
            throw e;
        }
        LOG.log(DEBUG, () -> "Attempting to resolve " + e);
        return resolve(e);
    }
    
    private Optional<Response> resolve(Exception e) throws Exception {
        try {
            if (handlers.isEmpty()) {
                return of(BASE.apply(e, null, null));
            }
            var rsp = usingHandlers(e);
            return requireWriterConformance(rsp, "Error");
        } catch (Exception fromChain) {
            e.addSuppressed(fromChain);
            LOG.log(ERROR, "Error processing chain failed to handle this", e);
            child.close();
            throw e;
        }
    }
    
    private Response usingHandlers(Exception e) {
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
                        .map(DefaultRequest::requestWithoutParams)
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
    
    /**
     * Tries to discard remaining message data in the channel.<p>
     * 
     * If discarding is not possible nor desired, for example, there is too much
     * remaining data; then this method returns an {@code Optional} with the
     * reason why. A non-empty {@code Optional} necessitates that the call-site
     * schedules the channel to be closed in the future or closes the channel
     * immediately.<p>
     * 
     * If discarding is possible, desired, and {@code dryRun} is {@code false},
     * then this method will attempt to discard the remaining data. If
     * discarding fails, this method will ensure that the exception is logged
     * and the channel is closed, before returning exceptionally. On success, or
     * if discarding is not needed, an empty {@code Optional} is returned.<p>
     * 
     * If {@code dryRun} is {@code true}, then this method will not have an
     * effect, but nothing is changed regarding the semantics of the return
     * value.
     * 
     * @implNote
     * This method does not check the shut-down state of the input stream.
     * Firstly, a closed input stream propagates as a "non-persistent
     * connection" in {@link ResponseProcessor}. Secondly, {@link #begin0()}
     * does not even try to discard if the connection is half-closed. So, in
     * both cases, there is no need for us to bother, and if we had, it would
     * only have been duplicated work.
     * 
     * @param r the request
     * @param dryRun whether this method should have an effect
     * 
     * @return the reason why remaining data can not be discarded
     * 
     * @throws IOException
     *             if an I/O error occurs
     */
    private Optional<String> tryDiscardRequest(
            SkeletonRequest r, boolean dryRun) throws IOException {
        // Note: ChunkedDecoder returns -1 until empty, then it returns 0...
        final long remaining = r.body().length();
        if (remaining == -1) {
            return of("unknown length of request data is remaining");
        }
        if (remaining >= 666) {
            return of("a satanic volume of request data is remaining");
        }
        if (remaining == 0) {
            // ...and so the body could have been chunked, and trailers could follow
            return switch (r.trParsingStatus()) {
                case NOT_APPLICABLE, SUCCESS ->
                       empty();
                case NOT_STARTED -> {
                       if (!dryRun) discardTrailers(r);
                       yield empty(); }
                case FAILED ->
                       of("trailers failed to parse");
            };
        } else {
            // HTTP/1.1 requires chunking for trailers, and we have a known
            // length, and so no trailers to discard
            // TODO: For HTTP/2, we may not be able to make the same assumption
            if (!dryRun) discardBody(r);
            return empty();
        }
    }
    
    private void discardTrailers(SkeletonRequest req) throws IOException {
        LOG.log(DEBUG, "Discarding request trailers");
        try {
            req.trailers();
        } catch (HeaderParseException | MaxRequestTrailersSizeException e) {
            // DEBUG because the app was obviously not interested in the request
            LOG.log(DEBUG, """
                Error while discarding request trailers, \
                shutting down the input stream.""", e);
            child.shutdownInput();
            throw e;
        }
    }
    
    private void discardBody(SkeletonRequest req) throws IOException {
        LOG.log(DEBUG, "Discarding request body before new exchange");
        try {
            req.body().iterator().forEachRemaining(buf ->
                    buf.position(buf.limit()));
        } catch (IOException e) {
            // DEBUG because the app was obviously not interested in the request
            LOG.log(DEBUG, "I/O error while discarding request body.", e);
            assert !child.isInputOpen();
            throw e;
        }
        assert req.body().isEmpty();
    }
    
    private void closeChannel(String why) {
        closeChannel(DEBUG, why);
    }
    
    private void closeChannel(Level level, String why) {
        if (child.isInputOpen() || child.isOutputOpen()) {
            LOG.log(level, () -> "Closing the channel because " + why + ".");
            child.close();
        }
    }
    
    private void closeChannel(Level level, String why, Exception exc) {
        if (child.isInputOpen() || child.isOutputOpen()) {
            LOG.log(level, () -> why + " (closing channel).", exc);
            child.close();
        } else {
            LOG.log(level, () -> why + ".", exc);
        }
    }
}