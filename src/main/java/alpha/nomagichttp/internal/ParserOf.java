package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.DefaultContentHeaders;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.MaxRequestHeadSizeException;
import alpha.nomagichttp.message.MaxRequestTrailersSizeException;
import alpha.nomagichttp.message.Request;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

import static java.lang.System.Logger.Level.DEBUG;

/**
 * A parser of headers or trailers (same thing).<p>
 * 
 * This parser interprets the HTTP line terminator the same as is done and
 * documented by the {@link ParserOfRequestLine}'s parser (see section "General
 * rules"). This parser also follows the contract defined by
 * {@link BetterHeaders}, e.g. header names must not be empty but the values
 * can.
 * 
 * <h2>Header names</h2>
 * 
 * Citation from
 * <a href="https://tools.ietf.org/html/rfc7230#section-3.2.4">RFC 7230 §3.2.4</a>:
 * 
 * <blockquote>
 *     No whitespace is allowed between the header field-name and colon.
 * </blockquote>
 * 
 * One could argue semantically that whitespace is not allowed inside the header
 * name (what would make or not make the whitespace before the colon be a part
 * of a legal field name?). I haven't found anything conclusive for or against
 * whitespace in header names. It does, however, seem to be extremely uncommon
 * and will complicate the processor logic. It further appears to be banned in
 * HTTP/2 and other projects (with zero documentation and exception handling)
 * will crash [unexpectedly] when processing whitespace in header names. Netty
 * 4.1.48 even hangs indefinitely lol.<p>
 * 
 * This parser will not allow whitespace in header names.<p>
 * 
 * References: <br>
 * https://github.com/bbyars/mountebank/issues/282 <br>
 * https://stackoverflow.com/a/56047701/1268003 <br>
 * https://stackoverflow.com/questions/50179659/what-is-considered-as-whitespace-in-http-header
 * 
 * <h2>Header values</h2>
 * 
 * Empty header values are allowed.<p>
 * 
 * Line folding is deprecated for all header values except media types. Why? I
 * do not know. What I do know is that allowing line folding for some but not
 * all is obviously complicating things. This processor will allow it for all
 * headers.<p>
 * 
 * References: <br>
 * https://github.com/eclipse/jetty.project/issues/1116 <br>
 * https://stackoverflow.com/a/31324422/1268003
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @param <H> parsed header's type
 * 
 * @see SkeletonRequest.TrParsingStatus
 */
final class ParserOf<H extends BetterHeaders> extends AbstractResultParser<H>
{
    private static final System.Logger LOG
            = System.getLogger(ParserOf.class.getPackageName());
    
    /**
     * Creates a parser of request headers.<p>
     * 
     * If the delta between the two given integers are exceeded while parsing,
     * the parser will throw a {@link MaxRequestHeadSizeException}.
     * 
     * @param in byte source
     * @param reqLineLen number of bytes already parsed from the head
     * @param maxHeadSize max total bytes to parse for a head
     * 
     * @return a parser of request headers
     */
    static ParserOf<Request.Headers>
            headers(ByteBufferIterable in, int reqLineLen, int maxHeadSize)
    {
        return new ParserOf<>(
                in, reqLineLen,
                maxHeadSize - reqLineLen,
                MaxRequestHeadSizeException::new,
                RequestHeaders.EMPTY,
                RequestHeaders::new);
    }
    
    /**
     * Creates a parser of request trailers.<p>
     * 
     * If {@code maxTrailersSize} is exceeded while parsing, the parser will
     * throw a {@link MaxRequestTrailersSizeException}.
     * 
     * @param in byte source
     * @param maxTrailersSize max bytes to parse
     * @return a parser of request trailers
     */
    static ParserOf<BetterHeaders>
            trailers(ByteBufferIterable in, int maxTrailersSize)
    {
        return new ParserOf<>(
                in, -1,
                maxTrailersSize,
                MaxRequestTrailersSizeException::new,
                DefaultContentHeaders.empty(),
                map -> new DefaultContentHeaders(map, true));
    }
    
    private final int logicalPos, maxBytes;
    // Should be ? extends AbstractSizeException (not public)
    private final IntFunction<? extends RuntimeException> exceeded;
    private final H empty;
    private final Function<LinkedHashMap<String, List<String>>, ? extends H> finisher;
    private final TokenParser parser;
    
    private ParserOf(
            ByteBufferIterable in,
            int logicalPos,
            int maxBytes,
            IntFunction<? extends RuntimeException> exceeded,
            H empty,
            Function<LinkedHashMap<String, List<String>>, ? extends H> finisher)
    {
        super(in);
        this.logicalPos = logicalPos;
        this.maxBytes = maxBytes;
        assert maxBytes >= 0;
        this.exceeded = exceeded;
        this.empty = empty;
        this.finisher = finisher;
        this.parser = new TokenParser();
    }
    
    @Override
    int position() {
        return logicalPos == -1 ? -1 : logicalPos + super.position();
    }
    
    @Override
    protected H tryParse(byte b) throws HeaderParseException {
        final int n = byteCount();
        if (n == maxBytes) {
            throw exceeded.apply(maxBytes);
        }
        return parser.parse(b);
    }
    
    @Override
    protected RuntimeException parseException(String msg) {
        return new HeaderParseException(
                msg, parser.previous(), parser.current(), position(), byteCount());
    }
    
    private static final int
            NAME = 0, VAL = 1, FOLD = 2, DONE = 3;
    
    private final class TokenParser extends AbstractTokenParser {
        TokenParser() {
            super(ParserOf.this::parseException);
        }
        
        private int parsing = NAME;
        private LinkedHashMap<String, List<String>> headers;
        
        H parse(byte b) {
            current(b);
            parsing = switch (parsing) {
                case NAME -> parseName();
                case VAL  -> parseVal();
                case FOLD -> parseValFolded();
                default -> throw new AssertionError("Not parsing anything lol.");
            };
            previous(b);
            return parsing == DONE ? build() : null;
        }
        
        private String name;
        int parseName() {
            if (isLeadingWhitespace() && isNotCR() && isNotLF()) {
                if (headers == null) {
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
                if (headers == null) {
                    headers = new LinkedHashMap<>();
                }
                // Will strip trailing whitespace in build()
                // (value can be folded)
                headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(v);
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
                List<String> v = headers.get(name);
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
        
        private H build() {
            if (headers == null) {
                return empty;
            }
            try {
                return finisher.apply(headers);
            } catch (IllegalArgumentException cause) {
                var t = new HeaderParseException(null, (byte) -1, (byte) -1, -1, byteCount());
                t.initCause(cause);
                throw t;
            }
        }
    }
}