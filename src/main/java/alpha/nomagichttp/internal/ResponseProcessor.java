package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.ResourceByteBufferIterable;
import alpha.nomagichttp.message.Response;

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

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
import static alpha.nomagichttp.handler.ClientChannel.tryAddConnectionClose;
import static alpha.nomagichttp.internal.HttpExchange.skeletonRequest;
import static alpha.nomagichttp.util.Blah.getOrCloseResource;
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
     * non-reentrant mutex involved (weird, but still).<p>
     * 
     * The reason why this method calls {@code iterator} is primarily to
     * lock down the response body length.
     * 
     * @param app the original
     * @param req the request (may be {@code null})
     * @param reqVer client/request HTTP version (may be {@code null})
     * 
     * @return see JavaDoc
     * 
     * @throws InterruptedException
     *             from {@link ResourceByteBufferIterable#iterator()}
     * @throws TimeoutException
     *             from {@link ResourceByteBufferIterable#iterator()}
     * @throws IOException
     *             from {@link ResourceByteBufferIterable#iterator()}, or
     *             from {@link ResourceByteBufferIterable#length()}
     */
    static Result process(Response app, SkeletonRequest req, Version reqVer)
            throws InterruptedException, TimeoutException, IOException {
        Response mod1 = tryCloseNonPersistentConn(app, req, reqVer);
        final var upstream = mod1.body();
        final var it = upstream.iterator();
        return getOrCloseResource(() -> {
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
        // But this is an unreliable method best to avoid (RFC 7230 §3.3.3.).
        LOG.log(DEBUG, """
                Response trailers and/or unknown body length; \
                applying chunked encoding""");
        if (r.headers().contains(TRANSFER_ENCODING)) {
            // TODO: We are going to implement various codecs,
            //       and then we can just append "chunked"?
            // Note; it's okay to repeat TE; RFC 7230, 3.2.2, 3.3.1.
            // But more clean to append.
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
    
    private static Response ensureCorrectFraming(Response r, long len) {
        if (r.headers().contains(TRANSFER_ENCODING)) {
            return dealWithTransferEncoding(r);
        }
        assert len >= 0 : "If <= -1, transfer encoding chunked was applied";
        final var reqMethod = skeletonRequest()
                .map(req -> req.head().line().method());
        if (reqMethod.filter(HEAD::equals).isPresent()) {
            return dealWithHeadRequest(r, len);
        }
        if (r.statusCode() == THREE_HUNDRED_FOUR) {
            return dealWith304(r, len);
        }
        final var cLen = r.headers().contentLength();
        if (cLen.isPresent()) {
            return dealWithCL(r, reqMethod, cLen.getAsLong(), len);
        } else if (len == 0) {
            return dealWithNoCLNoBody(r, reqMethod);
        } else {
            return dealWithNoCLHasBody(r, len);
        }
    }
    
    private static Response dealWithTransferEncoding(Response r) {
        if (r.isInformational()) {
            throw new IllegalArgumentException(
                    TRANSFER_ENCODING + " header in 1xx response");
        } else if (r.statusCode() == TWO_HUNDRED_FOUR) {
            throw new IllegalArgumentException(
                    TRANSFER_ENCODING + " header in 204 response");
        }
        // "A sender MUST NOT send a Content-Length header field in any message
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
    
    private static Response dealWithHeadRequest(Response r, long actualLen) {
        // We don't care about the presence of Content-Length, but:
        if (actualLen != 0) {
            throw new IllegalResponseBodyException(
                    "Possibly non-empty body in response to a $1 request."
                    .replace("$1", HEAD), r);
        }
        // "A server MAY send a Content-Length header field in a response to
        //  a HEAD request" (RFC 7230 §3.3.2)
        // 
        // "the response terminates at the end of the header section"
        // (RFC 7231 §4.3.2)
        return r;
    }
    
    private static Response dealWith304(Response r, long actualLen) {
        // "A 304 response cannot contain a message-body" (RFC 7234 §4.1)
        if (actualLen != 0) {
            throw new IllegalResponseBodyException(
                    "Possibly non-empty body in $1 response"
                    .replace("$1", valueOf(THREE_HUNDRED_FOUR)), r);
        }
        // "A server MAY send a Content-Length header field in a 304 (Not
        //  Modified) response to a conditional GET request" (RFC 7230 §3.3.2)
        // 
        // "A 304 response [...] is always terminated by the first empty line
        //  after the header fields." (RFC 7232 §4.1)
        return r;
    }
    
    private static final String
            BODY_IN_1XX = "Body in 1xx response",
            BODY_IN_204 = "Body in 204 response";
    
    private static Response dealWithCL(
            Response r, Optional<String> reqMethod,
            long cLen, long actualLen)
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
                if (CONNECT.equals(reqMethod.orElse(""))) {
                    throw new IllegalArgumentException(
                            "$1 header in response to a $2 request"
                            .replace("$1", CONTENT_LENGTH)
                            .replace("$2", CONNECT));
                }
            }
            if (cLen != actualLen) {
                throw new IllegalArgumentException(
                      "Discrepancy between $1=$2 and actual body length $3"
                      .replace("$1", CONTENT_LENGTH)
                      .replace("$2", valueOf(cLen))
                      .replace("$3", valueOf(actualLen)));
            }
            // Use the given content-length
            return r;
    }
    
    private static Response dealWithNoCLNoBody(
            Response r, Optional<String> rMethod)
    {
        return r.isInformational() ||
               r.statusCode() == TWO_HUNDRED_FOUR ||
               rMethod.filter(CONNECT::equals).isPresent() ?
                   r :
                   r.toBuilder().setHeader(CONTENT_LENGTH, "0").build();
    }
    
    private static Response dealWithNoCLHasBody(Response r, long actualLen) {
         if (r.isInformational()) {
             throw new IllegalResponseBodyException(BODY_IN_1XX, r);
         }
         if (r.statusCode() == TWO_HUNDRED_FOUR) {
             throw new IllegalResponseBodyException(BODY_IN_204, r);
         }
         // Would be weird if the request method is CONNECT??? (RFC 7231 §4.3.6)
        return r.toBuilder()
            .setHeader(CONTENT_LENGTH, valueOf(actualLen))
            .build();
    }
}