package alpha.nomagichttp.core;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.ResourceByteBufferIterable;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.FileLockTimeoutException;

import java.io.Closeable;
import java.io.IOException;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderName.TRAILER;
import static alpha.nomagichttp.HttpConstants.HeaderName.TRANSFER_ENCODING;
import static alpha.nomagichttp.HttpConstants.Method.CONNECT;
import static alpha.nomagichttp.HttpConstants.Method.HEAD;
import static alpha.nomagichttp.HttpConstants.StatusCode.THREE_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.isClientError;
import static alpha.nomagichttp.HttpConstants.StatusCode.isServerError;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.core.HttpExchange.skeletonRequest;
import static alpha.nomagichttp.handler.ClientChannel.tryAddConnectionClose;
import static alpha.nomagichttp.util.Blah.getOrClose;
import static alpha.nomagichttp.util.Blah.throwsNoChecked;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static alpha.nomagichttp.util.ScopedValues.httpServer;
import static java.lang.String.valueOf;
import static java.lang.System.Logger.Level.DEBUG;

/**
 * Makes a response ready for transmission.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponseProcessor
{
    private static final System.Logger LOG
            = System.getLogger(ResponseProcessor.class.getPackageName());
    
    private ResponseProcessor() {
        // Empty
    }
    
    record Result (
            Response response,
            ByteBufferIterator body,
            boolean closeChannel) implements Closeable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }
    
    /**
     * Ensures the given response is ready for transmission.<p>
     * 
     * This method semantically performs a set of privileged after-actions.
     * Specifically, the method will — if needed — apply chunked encoding, set
     * the "Connection: close" header for non-persistent connections, and ensure
     * that the response is properly delimited
     * (Transfer-Encoding/Content-Length).<p>
     * 
     * This method will call {@code Response.Body.iterator()}, and return the
     * same iterator — or a decorator — in the returned result object, which is
     * the reference the writer must use when writing the response body. The
     * result's response object must only be used to build a status line,
     * headers and trailers.<p>
     * 
     * The call to {@code iterator} may open system resources, and so, the
     * writer must close the returned result object, or the iterator contained
     * within. The writer must <i>not</i> call the body's {@code iterator}
     * method, again, as this could cause the application's response body
     * implementation to unnecessarily open more system resources. Even worse, a
     * nested call to {@code iterator} could forever block if there's a
     * non-reentrant mutex involved (which would be weird, but still).<p>
     * 
     * The reason why this method calls {@code iterator} is primarily to
     * lock down the response body length.
     * 
     * @param app the original
     * @param req the request (may be {@code null})
     * @param reqVer client/request HTTP version (may be {@code null})
     * 
     * @return the result
     * 
     * @throws InterruptedException
     *             from {@link ResourceByteBufferIterable#iterator()}
     * @throws FileLockTimeoutException
     *             from {@link ResourceByteBufferIterable#iterator()}
     * @throws IOException
     *             from {@link ResourceByteBufferIterable#iterator()}, or
     *             from {@link ResourceByteBufferIterable#length()}
     */
    static Result process(Response app, SkeletonRequest req, Version reqVer)
            throws InterruptedException, FileLockTimeoutException, IOException {
        Response mod1 = tryCloseNonPersistentConn(app, req, reqVer);
        final var upstream = mod1.body();
        final var it = upstream.iterator();
        return getOrClose(() -> {
            long len = upstream.length();
            final Response mod2 = tryChunkedEncoding(mod1, reqVer, len, it);
            ByteBufferIterator bodyToWrite = it;
            if (mod2.body() != upstream) {
                len = mod2.body().length();
                assert len == -1 : "Decorated by single-use de-/inflating codec";
                bodyToWrite = throwsNoChecked(() -> mod2.body().iterator());
            }
            final Response mod3 = ensureCorrectFraming(mod2, len);
            return new Result(
                    // Content
                    mod3, bodyToWrite,
                    // closeChannel
                    trackErrorResponses(mod2));
        }, it);
    }
    
    /**
     * Sets the "Connection: close" header on the given response, if the
     * connection is deemed not persistent.<p>
     * 
     * The connection is deemed not persistent if any one of these conditions
     * is true:
     * <ul>
     *   <li>The request is not available (null)</li>
     *   <li>The request version is older than HTTP/1.1</li>
     *   <li>The request has "Connection: close"</li>
     *   <li>The input stream is closed</li>
     *   <li>The HTTP server is not running</li>
     * </ul>
     * 
     * The request may not be available due to an <i>early</i> error.
     * Technically, some of these cases require a closing of the connection (for
     * example, an incorrect message syntax; delimiter lost), but in other cases
     * we could have saved the connection (for example, by discarding an illegal
     * body in a TRACE request). However, for early errors, all connections will
     * be indiscriminately closed, primarily as a means to simplify the code
     * base. Early errors are infrequent (compared to valid requests), and may
     * even indicate a malicious actor. So the harm done is limited and may even
     * be advantageous. One must also have in mind that our ability to host
     * connections is limited; any opportunity really we get to kick the
     * connection then we should.<p>
     * 
     * There are <i>late errors</i> that may occur after the request processing
     * chain has been invoked, which all of them should result in a
     * subject-relevant fallback response. Closing for late errors is determined
     * by {@link Config#maxErrorResponses()}, and implemented by
     * {@link #trackErrorResponses(Response)}.<p>
     * 
     * Noteworthy: The skeleton request may also not be available because
     * {@link HttpExchange} rejected the version; it was too old, or it was too
     * new. Today there's really no way we could save that connection. If the
     * version was too old, well, voila; HTTP/1.0 (and older) does not support
     * persistent connections, nor do we support the unofficial "keep-alive"
     * mechanism. Version was too new? Erm, this would actually be weird, as
     * the client ought to connect first using HTTP/1.1 which then upgrades to
     * HTTP/2 (unless the client already knows the server supports HTTP/2) —
     * either way, there's no "Downgrade" header lol. So again, still makes
     * sense to indiscriminately close the connection.<p>
     * 
     * More noteworthy stuff: It is very likely that when we add support for
     * protocol upgrading — aka. 101 (Switching Protocols) — the
     * {@link HttpVersionTooOldException} exception will still represent a
     * terminal failure for the connection (upgrading failed). What happens on
     * successful upgrading we shall wait and see.<p>
     * 
     * Even more noteworthy stuff: The {@code HttpExchange} may preempt this
     * class with a "Connection: close" if it is possible for the exchange to
     * determine that it will not be able to discard an unconsumed request.
     * 
     * @param rsp response
     * @param reqVer request version
     * 
     * @return possibly a modified response
     */
    private static Response tryCloseNonPersistentConn(
            Response rsp, SkeletonRequest req, Version reqVer) {
        if (rsp.isInformational() || rsp.headers().hasConnectionClose()) {
            return rsp;
        }
        final String why =
            req == null ? "no request is available (early error)" :
            reqVer.isLessThan(HTTP_1_1) ? reqVer + " does not support a persistent connection" :
            req.head().headers().hasConnectionClose() ? "the request headers' did" :
            !channel().isInputOpen() ? "the client's input stream has shut down" :
            !httpServer().isRunning() ? "the server has stopped" : null;
        return why == null ? rsp :
                tryAddConnectionClose(rsp, LOG, DEBUG, why);
    }
    
    private static Response tryChunkedEncoding(
            Response r, Version reqVer, long len, ByteBufferIterator body) {
        final boolean trPresent = r.headers().contains(TRAILER);
        if (reqVer == null || reqVer.isLessThan(HTTP_1_1)) {
            // Can not do chunking for unknown length, but:
            if (trPresent) {
                LOG.log(DEBUG, """
                    Client has no support for response trailers, \
                    discarding them""");
                return r.toBuilder().removeTrailers().build();
            }
            return r;
        }
        if (!trPresent && len >= 0 ) {
            // No trailers and a known length make chunking unnecessary
            return r;
        }
        // We could mark the end of a response by closing the connection.
        // But this is an unreliable method best to avoid (RFC 9112 §6.3).
        LOG.log(DEBUG, """
                Response trailers and/or unknown body length; \
                applying chunked encoding""");
        if (r.headers().contains(TRANSFER_ENCODING)) {
            // TODO: We are going to implement various codecs,
            //       and then we can just append "chunked"? ... 
            // Note; it's okay to repeat T-E (RFC 9110 §5.3)
            // But more clean to append (RFC 9112 §6.1).
            throw new IllegalArgumentException(
                "Transfer-Encoding in response was not expected");
        }
        return r.toBuilder()
                .setHeader(TRANSFER_ENCODING, "chunked")
                .body(new ChunkedEncoder(body))
                .build();
    }
    
    private static boolean trackErrorResponses(Response r) {
        final var key = "alpha.nomagichttp.dcw.nUnsuccessful";
        if (isClientError(r.statusCode()) || isServerError(r.statusCode())) {
            // Bump error counter
            int n = channel().attributes().<Integer>asMapAny().merge(key, 1, Integer::sum);
            return n >= httpServer().getConfig().maxErrorResponses();
        } else {
            // Reset
            channel().attributes().set(key, 0);
        }
        return false;
    }
    
    private static final String ASSERT_NO_BODY
            = "Response.Builder.build() should have thrown" +
              IllegalResponseBodyException.class.getSimpleName();
    
    //  For relevant RFC rules, see MessageFramingTest.
    private static Response ensureCorrectFraming(Response r, long len) {
        final String method = skeletonRequest()
                .map(req -> req.head().line().method()).orElse(null);
        if (HEAD.equals(method)) {
            validateHeadRequest(r, len);
        }
        if (r.headers().contains(TRANSFER_ENCODING)) {
            validateTransferEncoding(r, method);
            // With transfer-encoding, framing is done
            return r;
        }
        if (r.statusCode() == THREE_HUNDRED_FOUR) {
            assert len == 0 : ASSERT_NO_BODY;
            return r;
        }
        assert len >= 0 : "If <= -1, transfer encoding chunked was applied";
        final var cLen = r.headers().contentLength();
        if (cLen.isPresent()) {
            validateCL(r, method, cLen.getAsLong(), len);
            // Use the given content-length
            return r;
        } else if (len == 0) {
            return dealWithNoCLNoBody(r, method);
        } else {
            return dealWithNoCLHasBody(r, len);
        }
    }
    
    private static void validateHeadRequest(Response r, long actualLen) {
        if (actualLen != 0) {
            throw new IllegalResponseBodyException(
                    "Possibly non-empty body in response to a $1 request."
                    .replace("$1", HEAD), r);
        }
    }
    
    private static void validateTransferEncoding(
            Response r, String method) 
    {
        if (r.isInformational()) {
            throw new IllegalArgumentException(
                    TRANSFER_ENCODING + " header in 1xx response");
        } else if (r.statusCode() == TWO_HUNDRED_FOUR) {
            throw new IllegalArgumentException(
                    TRANSFER_ENCODING + " header in 204 response");
        } else if (r.isSuccessful() && CONNECT.equals(method)) {
            throw new IllegalArgumentException(
                    "$1 header in 2xx response to a $2 request"
                        .replace("$1", TRANSFER_ENCODING)
                        .replace("$2", CONNECT));
        }
        if (r.headers().contentLength().isPresent()) {
            throw new IllegalArgumentException(
                    "Both $1 and $2 headers are present."
                    .replace("$1", TRANSFER_ENCODING)
                    .replace("$2", CONTENT_LENGTH));
        }
    }
    
    private static void validateCL(
            Response r, String method, long cLen, long actualLen)
    {
            if (r.isInformational()) {
                if (actualLen == 0) {
                    throw new IllegalArgumentException(
                            CONTENT_LENGTH + " header in 1xx response");
                } else {
                    throw new AssertionError(ASSERT_NO_BODY);
                }
            } else if (r.statusCode() == TWO_HUNDRED_FOUR) {
                if (actualLen == 0) {
                    throw new IllegalArgumentException(
                        CONTENT_LENGTH + " header in 204 response");
                } else {
                    throw new AssertionError(ASSERT_NO_BODY);
                }
            } else if (r.isSuccessful()) {
                if (CONNECT.equals(method)) {
                    throw new IllegalArgumentException(
                            "$1 header in 2xx response to a $2 request"
                            .replace("$1", CONTENT_LENGTH)
                            .replace("$2", CONNECT));
                }
            }
            if (!HEAD.equals(method) && cLen != actualLen) {
                throw new IllegalArgumentException(
                      "Discrepancy between $1=$2 and actual body length $3"
                      .replace("$1", CONTENT_LENGTH)
                      .replace("$2", valueOf(cLen))
                      .replace("$3", valueOf(actualLen)));
            }
    }
    
    private static Response dealWithNoCLNoBody(
            Response r, String method)
    {
        return r.isInformational() ||
               r.statusCode() == TWO_HUNDRED_FOUR ||
               (r.isSuccessful() && CONNECT.equals(method)) ?
                   r :
                   r.toBuilder().setHeader(CONTENT_LENGTH, "0").build();
    }
    
    private static Response dealWithNoCLHasBody(Response r, long actualLen) {
        assert !r.isInformational() : ASSERT_NO_BODY;
        assert r.statusCode() != TWO_HUNDRED_FOUR : ASSERT_NO_BODY;
        // Would be weird if the request method is CONNECT??? (RFC 7231 §4.3.6)
        return r.toBuilder()
                .setHeader(CONTENT_LENGTH, valueOf(actualLen))
                .build();
    }
}
