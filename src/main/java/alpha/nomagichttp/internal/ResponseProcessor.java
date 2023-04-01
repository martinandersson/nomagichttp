package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.HeaderHolder;
import alpha.nomagichttp.message.IllegalResponseBodyException;
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
 * Applies message delimiting.<p>
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
    
    Result process(Response app, Version httpVer)
            throws InterruptedException, TimeoutException, IOException {
        // We call iterator and length only once, for several reasons.
        // We need a reliable length, so there must be one global iteration.
        // And a nested call to iterator could forever block if there's a
        // non-reentrant mutex involved (weird, but still).
        // Improved performance is a nice bonus (e.g. just one File.size
        // call)
        var upstream = app.body();
        var it = upstream.iterator();
        return getOrCloseResource(() -> {
            long len = upstream.length();
            Response mod = closeIfOldHttp(app, httpVer);
            mod = tryChunkedEncoding(mod, httpVer, len, it);
            // TODO: Extremely ugly, needs refactoring
            ByteBufferIterator whatToWrite = it;
            if (mod.body() != upstream) {
                len = mod.body().length();
                assert len == -1 : "Decorated by de-/inflating codec";
                try {
                    whatToWrite = mod.body().iterator();
                } catch (InterruptedException | TimeoutException | IOException e) {
                    throw new AssertionError(e);
                }
            }
            mod = trackConnectionClose(mod, scheduledClose);
            mod = ensureCorrectFraming(mod, len);
            boolean closeChannel = trackUnsuccessful(mod);
            return new Result(
                    mod, whatToWrite, mod.isFinal() && sawConnectionClose, closeChannel);
        }, it);
    }
    
    void scheduleClose(String reason) {
        if (scheduledClose == null) {
            scheduledClose = reason;
        }
    }
    
    private static Response closeIfOldHttp(Response r, Version httpVer) {
        // No support for HTTP 1.0 Keep-Alive
        if (r.isFinal() &&
            httpVer.isLessThan(HTTP_1_1) &&
            !__rspHasConnectionClose(r))
        {
            return __setConnectionClose(r);
        }
        return r;
    }
    
    private static Response tryChunkedEncoding(Response r, Version httpVer, long len, ByteBufferIterator body) {
        final boolean trailersPresent = r.headers().contains(TRAILER);
        if (httpVer.isLessThan(HTTP_1_1)) {
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
    private Response trackConnectionClose(Response r, String scheduledClose) {
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