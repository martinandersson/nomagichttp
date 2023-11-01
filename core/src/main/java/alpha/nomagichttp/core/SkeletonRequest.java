package alpha.nomagichttp.core;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.message.Attributes;
import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.RawRequest;

import java.io.IOException;

import static alpha.nomagichttp.core.SkeletonRequest.TrParsingStatus.FAILED;
import static alpha.nomagichttp.core.SkeletonRequest.TrParsingStatus.NOT_APPLICABLE;
import static alpha.nomagichttp.core.SkeletonRequest.TrParsingStatus.NOT_STARTED;
import static alpha.nomagichttp.core.SkeletonRequest.TrParsingStatus.SUCCESS;
import static alpha.nomagichttp.message.DefaultContentHeaders.empty;
import static alpha.nomagichttp.util.ScopedValues.httpServer;

/**
 * A thin version of a request.<p>
 * 
 * This class contains almost all components needed for a complete
 * {@link DefaultRequest}. The one thing missing is path parameters.
 * 
 * @author Martin Andersson (webmaster@martinandersson.com)
 */
final class SkeletonRequest
{
    private final RawRequest.Head head;
    private final HttpConstants.Version httpVersion;
    private final SkeletonRequestTarget target;
    private final RequestBody body;
    private final ChannelReader reader;
    private final Attributes attributes;
    private BetterHeaders trailers;
    private TrParsingStatus status;
    
    SkeletonRequest(
            RawRequest.Head head,
            HttpConstants.Version httpVersion,
            SkeletonRequestTarget target,
            RequestBody body,
            ChannelReader reader) {
        this.head = head;
        this.httpVersion = httpVersion;
        this.target = target;
        this.body = body;
        this.reader = reader;
        attributes = new DefaultAttributes();
        trailers = null;
        // TODO: HTTP/2 may require a different strategy how expectation of trailers is computed
        status = head.headers().hasTransferEncodingChunked() ?
                     NOT_STARTED : NOT_APPLICABLE;
    }
    
    RawRequest.Head head() {
        return head;
    }
    HttpConstants.Version httpVersion() {
        return httpVersion;
    }
    SkeletonRequestTarget target() {
        return target;
    }
    RequestBody body() {
        return body;
    }
    
    BetterHeaders trailers() throws IOException {
        return switch (status) {
            case NOT_APPLICABLE -> empty();
            case NOT_STARTED -> (trailers = trailers0());
            case FAILED -> throw new IllegalStateException("Previous parsing failed");
            case SUCCESS -> trailers;
        };
    }
    
    private BetterHeaders trailers0() throws IOException {
        if (!body().isEmpty()) {
            throw new IllegalStateException("Consume the body first");
        }
        var maxLen = httpServer().getConfig().maxRequestTrailersSize();
        status = FAILED;
        final BetterHeaders h;
        try {
            h = ParserOf.trailers(reader, maxLen).parse();
        } finally {
            reader.limit(0);
        }
        status = SUCCESS;
        return h;
    }
    
    /**
     * Trailers' parsing status.<p>
     * 
     * The status functions primarily as an indicator to {@link HttpExchange}
     * whether the remaining request data in a channel can and should safely be
     * discarded, or if the message framing is corrupt.<p>
     * 
     * If request headers fail to parse, then {@link ResponseProcessor} sets
     * "Connection: close" for a quote unquote "early error", causing
     * {@link DefaultChannelWriter} to close the output stream, causing
     * {@code HttpExchange} to not even try to discard the request, and
     * eventually {@link DefaultServer} shuts down the channel.<p>
     * 
     * A high-level error from reading the request object is recoverable; does
     * not leave the message framing corrupt. A low-level error causes
     * {@link ChannelReader} to close the input stream, with the same
     * exchange-ending effect as for early errors; discarding does not happen
     * and the channel is closed.<p>
     * 
     * What may go wrong and leave the framing corrupt <i>after</i> the request
     * head and body, is the parsing of trailers, which is unrecoverable, and so
     * the connection must close.<p>
     * 
     * An HTTP/1.1 chunked body terminates with a final {@code CRLF} that
     * {@link ChunkedDecoder} does not consume, and so if the desire is to save
     * the connection, then trailers must be discarded, because that will cause
     * {@link ParserOf#trailers(ByteBufferIterable, int) ParserOf.trailers(...)}
     * to read away the terminating {@code CRLF}.
     */
    // TODO: HTTP/2 may require a different strategy how trailers are discarded
    enum TrParsingStatus {
        /**
         * The request does not support trailers.<p>
         * 
         * A new HTTP exchange may begin using the same connection; there are no
         * trailers to discard nor will there be a lingering, unconsumed
         * {@code CRLF} sequence in the input stream.
         */
        NOT_APPLICABLE,
        /**
         * Parsing of trailers has never started.<p>
         * 
         * The trailers must be discarded before a new HTTP exchange can begin.
         */
        NOT_STARTED,
        /**
         * Parsing of trailers was started, but failed to complete
         * successfully.<p>
         * 
         * The message framing is corrupt and the channel must close.
         */
        FAILED,
        /**
         * Trailers were successfully parsed (empty or not).<p>
         * 
         * A new HTTP exchange may commence over the same channel.
         */
        SUCCESS;
    }
    
    /**
     * {@return the parsing status of trailers}
     */
    TrParsingStatus trParsingStatus() {
        return status;
    }
    
    Attributes attributes() {
        return attributes;
    }
}