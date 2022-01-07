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
            NAME = 0, VAL = 1, FOLD = 2, DONE = 3;
    
    /**
     * Parses bytes into HTTP headers.<p>
     * 
     * This parser interprets the HTTP line terminator the same as is done and
     * documented by the {@link RequestLineSubscriber}'s parser (see section
     * "General rules"). The parser also follows the contract defined by {@link
     * BetterHeaders}, e.g. header names may not be empty but the values may.
     * 
     * 
     * <h2>Header names</h2>
     * 
     * Citation from
     * <a href="https://tools.ietf.org/html/rfc7230#section-3.2.4">RFC 7230 §3.2.4</a>
     * 
     * <blockquote>
     *     No whitespace is allowed between the header field-name and colon.
     * </blockquote>
     * 
     * One could argue semantically that whitespace is not allowed inside the
     * header name (what would make or not make the whitespace before the colon
     * be a part of a legal field name?). I haven't found anything conclusive
     * for or against whitespace in header names. It does, however, seem to be
     * extremely uncommon and will complicate the processor logic. It further
     * appears to be banned in HTTP/2 and other projects (with zero
     * documentation and exception handling) will crash [unexpectedly] when
     * processing whitespace in header names. Netty 4.1.48 even hangs
     * indefinitely.<p>
     * 
     * This parser will not allow whitespace in header names.<p>
     * 
     * References: <br>
     * https://github.com/bbyars/mountebank/issues/282 <br>
     * https://stackoverflow.com/a/56047701/1268003 <br>
     * https://stackoverflow.com/questions/50179659/what-is-considered-as-whitespace-in-http-header
     * 
     * 
     * <h2>Header values</h2>
     * 
     * Empty header values are allowed.<p>
     * 
     * Line folding is deprecated for all header values except media types. Why?
     * I do not know. What I do know is that allowing line folding for some but
     * not all is obviously complicating things. This processor will allow it
     * for all headers.<p>
     * 
     * References: <br>
     * https://github.com/eclipse/jetty.project/issues/1116 <br>
     * https://stackoverflow.com/a/31324422/1268003
     */
    private final class Parser extends AbstractTokenParser
    {
        private int parsing = NAME;
        private Map<String, List<String>> values;
        
        T parse(byte b) {
            curr = b;
            parsing = switch (parsing) {
                case NAME -> parseName();
                case VAL  -> parseVal();
                case FOLD -> parseValFolded();
                default -> throw new AssertionError("Not parsing anything lol.");
            };
            prev = curr;
            return parsing == DONE ? build() : null;
        }
        
        private String name;
        int parseName() {
            if (isLeadingWhitespace() && isNotCR() && isNotLF()) {
                if (values == null) {
                    throw parseException(
                        "Leading whitespace in header name is not accepted.");
                }
                LOG.log(DEBUG, "Resuming last [folded] header value.");
                return FOLD;
            }
            if (isCR()) {
                // Ignore. Will crash next iteration if CR isn't followed by LF.
                return NAME;
            }
            else if (isLF()) {
                if (hasConsumed()) {
                    throw parseException(
                        "Whitespace in header name or before colon is not accepted.");
                }
                return DONE;
            }
            else if (isWhitespace()) {
                throw parseException(
                    "Whitespace in header name or before colon is not accepted.");
            }
            else if (isColon()) {
                name = finishNonEmpty("header name");
                return VAL;
            }
            consume();
            return NAME;
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
                values.computeIfAbsent(name, k -> new ArrayList<>(1)).add(v);
                return NAME;
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
                List<String> v = values.get(name);
                String last = v.remove(v.size() - 1);
                
                if (!last.isEmpty()) {
                    boolean endsWithWS = Character.isWhitespace(
                            last.charAt(last.length() - 1));
                    consumeExplicit(endsWithWS ? last : last + ' ');
                }
                parsing = NAME;
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
            HttpHeaders h;
            try {
                h = Headers.of(values != null ? values : Map.of());
            } catch (IllegalArgumentException cause) {
                var t = new HeaderParseException(null, (byte) -1, (byte) -1, -1);
                t.initCause(cause);
                throw t;
            }
            return finisher.apply(h);
        }
    }
}