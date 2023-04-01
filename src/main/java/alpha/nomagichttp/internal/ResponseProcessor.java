package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.ResourceByteBufferIterable;
import alpha.nomagichttp.message.Response;

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONNECTION;
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
import static alpha.nomagichttp.internal.HttpExchange.skeletonRequest;
import static alpha.nomagichttp.util.Blah.getOrCloseResource;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static alpha.nomagichttp.util.ScopedValues.httpServer;
import static java.lang.String.valueOf;
import static java.lang.System.Logger.Level.DEBUG;

/**
 * Is semantically a set of privileged after-actions.<p>
 * 
 * Core responsibilities, if applicable:
 * <ul>
 *   <li>Set "Connection: close"</li>
 *   <li>Message delimiting (Transfer-Encoding/Content-Length)</li>
 * </ul>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponseProcessor
{
    private static final System.Logger LOG
            = System.getLogger(ResponseProcessor.class.getPackageName());
    
    private boolean sawConnectionClose;
    private String scheduledClose;
    
    record Result (
            Response response,
            ByteBufferIterator body,
            boolean closeConnection,
            boolean closeChannel) implements Closeable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }
    
    /**
     * Ensures the given response is ready for transmission.<p>
     * 
     * This method will call {@code Response.Body.iterator()}, and return the
     * same iterator — or a decorator — in the returned result object, which is
     * the reference the writer must use when writing the response body. The
     * result's response object must only be used to build a status-line,
     * retrieve headers and trailers.<p>
     * 
     * The call to {@code iterator} may open system resources, and so, the
     * writer must close the returned result object, or the iterator contained
     * within. The writer must <i>not</i> call the {@code iterator} method,
     * again, as this could cause the application's response body implementation
     * to unnecessarily open more system resources. Even worse, a nested call to
     * {@code iterator} could forever block if there's a non-reentrant mutex
     * involved (weird, but still).<p>
     * 
     * The reason why this method calls {@code iterator} is primarily to
     * lock-down the response body length.
     * 
     * @param app the original
     * @param httpVer HTTP version used by the current exchange
     * 
     * @return see JavaDoc
     * 
     * @throws InterruptedException
     *             from {@link ResourceByteBufferIterable#iterator()}
     * @throws TimeoutException
     *             from {@link ResourceByteBufferIterable#iterator()}
     * @throws IOException
     *             from {@link ResourceByteBufferIterable#iterator()}
     */
    Result process(Response app, Version httpVer)
            throws InterruptedException, TimeoutException, IOException {
        final var upstream = app.body();
        final var it = upstream.iterator();
        return getOrCloseResource(() -> {
            long len = upstream.length();
            Response mod = closeIfOldHttp(app, httpVer);
            mod = tryChunkedEncoding(mod, httpVer, len, it);
            ByteBufferIterator bodyToWrite = it;
            if (mod.body() != upstream) {
                len = mod.body().length();
                assert len == -1 : "Decorated by de-/inflating codec";
                try {
                    bodyToWrite = mod.body().iterator();
                } catch (InterruptedException | TimeoutException | IOException e) {
                    throw new AssertionError(e);
                }
            }
            mod = trackConnectionClose(mod);
            mod = ensureCorrectFraming(mod, len);
            return new Result(
                    // Contents
                    mod, bodyToWrite,
                    // closeConnection
                    mod.isFinal() && sawConnectionClose,
                    // closeChannel
                    trackUnsuccessful(mod));
        }, it);
    }
    
    void scheduleClose(String reason) {
        if (scheduledClose == null) {
            scheduledClose = reason;
        }
    }
    
    private static Response closeIfOldHttp(Response r, Version ver) {
        // No support for HTTP 1.0 Keep-Alive
        return r.isFinal() &&
               ver.isLessThan(HTTP_1_1) &&
               !r.headers().hasConnectionClose() ?
                   setConnectionClose(r) : r;
    }
    
    private static Response tryChunkedEncoding(
            Response r, Version ver, long len, ByteBufferIterator body) {
        final boolean trPresent = r.headers().contains(TRAILER);
        if (ver.isLessThan(HTTP_1_1)) {
            if (trPresent) {
                LOG.log(DEBUG, """
                    HTTP/1.0 has no support for response trailers, \
                    discarding them""");
                return r.toBuilder().removeTrailers().build();
            }
            // Connection will close; no need to do chunking
            return r;
        }
        if (!trPresent && len >= 0) {
            // No trailers and a known length makes chunking unnecessary
            return r;
        }
        // We could mark the end of a response by closing the connection.
        // But, this is an unreliable method best to avoid (RFC 7230 §3.3.3.).
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
            if (r.isFinal() && !r.headers().hasConnectionClose()) {
                LOG.log(DEBUG,
                    "Connection-close flag propagates to final response");
                out = setConnectionClose(r);
            } else {
                // Message not final or already has the header = NOP
                out = r;
            }
        } else if (r.headers().hasConnectionClose()) {
            // Set flag, but no need to modify response
            sawConnectionClose = true;
            out = r;
        } else if (!r.isFinal()) {
            // Haven't seen the header before and flag is false = NOP
            out = r;
        } else {
            final String why =
                requestHasConnClose()     ? "the request headers' did." :
                !channel().isInputOpen()  ? "the client's input stream has shut down." :
                !httpServer().isRunning() ? "the server has stopped." :
                scheduledClose;
            if (why != null) {
                LOG.log(DEBUG, () ->
                    "Will set \"Connection: close\" because " + why);
                sawConnectionClose = true;
                out = setConnectionClose(r);
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
            return dealWithTransferEncoding(r);
        }
        assert len >= 0 : "If <= -1, chunked encoding was applied";
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
    
    private static Response setConnectionClose(Response r) {
        return r.toBuilder().header(CONNECTION, "close").build();
    }
    
    private static boolean requestHasConnClose() {
        return skeletonRequest().map(req ->
                req.head().headers().hasConnectionClose()).orElse(false);
    }
    
    private static Response dealWithTransferEncoding(Response r) {
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
    
    private static Response dealWithHeadRequest(Response r, long actualLen) {
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
    
    private static Response dealWith304(Response r, long actualLen) {
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
                   r.toBuilder().header(CONTENT_LENGTH, "0").build();
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
            .header(CONTENT_LENGTH, valueOf(actualLen))
            .build();
    }
}