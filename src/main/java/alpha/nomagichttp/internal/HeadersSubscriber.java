package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.DefaultContentHeaders;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.MaxRequestTrailersSizeExceededException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Headers;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.DEBUG;

/**
 * Parse bytes into headers.<p>
 * 
 * Exceptions that originate from the channel will already have closed the
 * read stream. Any exception that originates from this class will close the
 * read stream; message framing lost.<p>
 * 
 * On parse error, the stage will complete with a {@link HeaderParseException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @param <T> parsed header's type
 */
final class HeadersSubscriber<T> extends AbstractByteSubscriber<T>
{
    private static final System.Logger LOG
            = System.getLogger(HeadersSubscriber.class.getPackageName());
    
    /**
     * Creates a subscriber of request headers.
     * 
     * If the delta between the two given integers are exceeded while parsing,
     * the result stage will complete exceptionally with a {@link
     * MaxRequestHeadSizeExceededException}.
     * 
     * @param requestLineLength number of bytes already parsed from the head
     * @param maxRequestHeadSize max total bytes to parse
     * @param chApi used for exceptional shutdown of read stream
     * @return a subscriber of request headers
     */
    public static HeadersSubscriber<Request.Headers> forRequestHeaders(
            int requestLineLength, int maxRequestHeadSize, ClientChannel chApi) {
        return new HeadersSubscriber<>(
                requestLineLength,
                maxRequestHeadSize - requestLineLength, chApi,
                MaxRequestHeadSizeExceededException::new,
                RequestHeaders::new);
    }
    
    /**
     * Creates a subscriber of request trailers.
     * 
     * If {@code maxTrailersSize} is exceeded while parsing, the result stage
     * will complete exceptionally with a {@link
     * MaxRequestTrailersSizeExceededException}.
     * 
     * @param maxTrailersSize max bytes to parse
     * @param chApi used for exceptional shutdown of read stream
     * @return a subscriber of request trailers
     */
    public static HeadersSubscriber<BetterHeaders> forRequestTrailers(
            int maxTrailersSize, ClientChannel chApi) {
        return new HeadersSubscriber<>(
                -1, maxTrailersSize, chApi,
                MaxRequestTrailersSizeExceededException::new,
                DefaultContentHeaders::new);
    }
    
    private final int posDelta, maxBytes;
    private final ClientChannel chApi;
    private final Supplier<? extends RuntimeException> exceeded;
    private final Function<HttpHeaders, ? extends T> finisher;
    private final Parser parser;
    
    private HeadersSubscriber(
            int posDelta,
            int maxBytes,
            ClientChannel chApi,
            Supplier<? extends RuntimeException> exceeded,
            Function<HttpHeaders, ? extends T> finisher)
    {
        this.posDelta = posDelta;
        this.maxBytes = maxBytes;
        this.chApi = chApi;
        assert maxBytes >= 0;
        assert chApi != null;
        this.exceeded = exceeded;
        this.finisher = finisher;
        this.parser = new Parser();
    }
    
    @Override
    protected T parse(byte b) {
        int r = read();
        if (r == maxBytes) {
            throw exceeded.get(); }
        try {
            return parser.parse(b);
        } catch (Throwable t) {
            if (chApi.isOpenForReading()) {
                LOG.log(DEBUG,
                    "Headers parsing failed, " +
                    "shutting down the channel's read stream.");
                chApi.shutdownInputSafe();
            }
            throw t;
        }
    }
    
    private static final int
            KEY = 0, VAL = 1, FOLD = 2, DONE = 3;
    
    private final class Parser extends AbstractTokenParser
    {
        private int parsing = KEY;
        private Map<String, List<String>> values;
        
        T parse(byte b) {
            curr = b;
            parsing = switch (parsing) {
                case KEY  -> parseKey();
                case VAL  -> parseVal();
                case FOLD -> parseValFolded();
                default -> throw new AssertionError("Not parsing anything lol.");
            };
            prev = curr;
            return parsing == DONE ? build() : null;
        }
        
        private String key;
        int parseKey() {
            if (isLeadingWhitespace() && isNotCR() && isNotLF()) {
                if (values == null) {
                    throw parseException(
                        "Leading whitespace in header key is not accepted.");
                }
                LOG.log(DEBUG, "Resuming last [folded] header value.");
                return FOLD;
            }
            if (isCR()) {
                // Ignore. Will crash next iteration if CR isn't followed by LF.
                return KEY;
            }
            else if (isLF()) {
                if (hasConsumed()) {
                    throw parseException(
                        "Whitespace in header key or before colon is not accepted.");
                }
                return DONE;
            }
            else if (isWhitespace()) {
                throw parseException(
                    "Whitespace in header key or before colon is not accepted.");
            }
            else if (isColon()) {
                key = finishNonEmpty("header key");
                return VAL;
            }
            consume();
            return KEY;
        }
        
        int parseVal() {
            // accept empty token so that the folding method knows what List to continue
            final String v = tryFinishOnLF();
            if (v == null) {
                // ignore all leading whitespace
                if (!isLeadingWhitespace()) {
                    consume();
                }
                return VAL;
            }
            else {
                if (values == null) {
                    values = new HashMap<>();
                }
                // Relying on HttpHeaders to trim the final value
                values.computeIfAbsent(key, k -> new ArrayList<>(1)).add(v);
                return KEY;
            }
        }
        
        int parseValFolded() {
            if (isLF()) {
                LOG.log(DEBUG,
                    "Unexpected LF when anticipating a folded header value. " +
                    "But, we forgive.");
                return DONE;
            } else if (!isWhitespace()) {
                // Restore header value and manually call the "standard" method
                List<String> v = values.get(key);
                String last = v.remove(v.size() - 1);
                
                if (!last.isEmpty()) {
                    boolean endsWithWS = Character.isWhitespace(
                            last.charAt(last.length() - 1));
                    consumeExplicit(endsWithWS ? last : last + ' ');
                }
                parsing = KEY;
                return parseVal();
            }
            // else ignore all leading whitespace
            return FOLD;
        }
        
        @Override
        protected HeaderParseException parseException(String msg) {
            int p = posDelta == -1 ? -1 : posDelta + read() - 1;
            return new HeaderParseException(msg, prev, curr, p);
        }
        
        private T build() {
            return finisher.apply(Headers.of(
                    values != null ? values : Map.of()));
        }
    }
}