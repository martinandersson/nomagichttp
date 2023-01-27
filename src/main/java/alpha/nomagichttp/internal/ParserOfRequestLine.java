package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.RequestLineParseException;

/**
 * A parser of {@link RawRequest.Line}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Document, impl, and test various forms for request-target?
//       https://datatracker.ietf.org/doc/html/rfc7230#section-5.3
final class ParserOfRequestLine extends AbstractResultParser<RawRequest.Line>
{
    private final int maxBytes;
    private final Parser parser;
    private long started;
    
    /**
     * Constructs this object.
     * 
     * @param in byte source
     * @param maxRequestHeadSize max bytes to parse
     */
    ParserOfRequestLine(ChannelReader in, int maxRequestHeadSize) {
        super(in);
        maxBytes = maxRequestHeadSize;
        parser = new Parser();
    }
    
    @Override
    protected RawRequest.Line parse(byte b)
            throws RequestLineParseException, MaxRequestHeadSizeExceededException
    {
        final int r = getCount();
        if (r == maxBytes) {
            throw new MaxRequestHeadSizeExceededException(); }
        if (r == 1) {
            started = System.nanoTime();}
        return parser.parse(b);
    }
    
    private static final int
            METHOD = 0, TARGET = 1, VERSION = 2, DONE = 3;
    
    /**
     * Parses bytes into a request-line.<p>
     * 
     * When left with a choice, this parser follows rather a lenient model than
     * a strict one.
     * 
     * 
     * <h2>General rules</h2>
     * 
     * Citation from <a href="https://tools.ietf.org/html/rfc7230#section-3.5">RFC
     * 7230 §3.5</a>
     *
     * <blockquote>
     *     Although the line terminator for the start-line and header fields is
     *     the sequence CRLF, a recipient MAY recognize a single LF as a line
     *     terminator and ignore any preceding CR.
     * </blockquote>
     * 
     * This parser ignores CR immediately preceding LF; it is never consumed as
     * part of the request. If anything else follows the CR an exception is
     * thrown, except for cases where it is either considered as a word boundary
     * (start-line) or ignored as leading whitespace.
     * 
     * 
     * <h2>Request-line rules</h2>
     *
     * Citation from <a href="https://tools.ietf.org/html/rfc7230#section-3.5">RFC
     * 7230 §3.5</a>
     *
     * <blockquote>
     *     Although the request-line and status-line grammar rules require that
     *     each of the component elements be separated by a single SP octet,
     *     recipients MAY instead parse on whitespace-delimited word boundaries
     *     and, aside from the CRLF terminator, treat any form of whitespace as
     *     the SP separator while ignoring preceding or trailing whitespace;
     *     such whitespace includes one or more of the following octets: SP,
     *     HTAB, VT (%x0B), FF (%x0C), or bare CR.
     * </blockquote>
     * 
     * This parser follows the "MAY" part.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    private final class Parser extends AbstractTokenParser {
        private int parsing = METHOD;
        
        RawRequest.Line parse(byte b) {
            curr = b;
            parsing = switch (parsing) {
                case METHOD  -> parseMethod();
                case TARGET  -> parseTarget();
                case VERSION -> parseVersion();
                default ->
                    throw new AssertionError("Not parsing anything lol.");
            };
            prev = curr;
            return parsing != DONE ? null :
                    new RawRequest.Line(method, rt, ver, started, getCount());
        }
        
        private String method;
        int parseMethod() {
            if (isLeadingWhitespace()) {
                // ignore all leading whitespace (including CR/LF)
                return METHOD;
            }
            requireIsNotLF();
            if (isWhitespace()) {
                // any whitespace, including CR terminates this token
                method = finishNonEmpty("method");
                return TARGET;
            }
            // Consume and expect more
            consume();
            return METHOD;
        }
        
        private String rt;
        int parseTarget() {
            requireIsNotLF();
            if (isLeadingWhitespace()) {
                return TARGET;
            }
            if (isWhitespace()) {
                rt = finishNonEmpty("request-target");
                return VERSION;
            }
            consume();
            return TARGET;
        }
        
        private String ver;
        int parseVersion() {
            if (isLeadingWhitespace() && isNotCR() && isNotLF()) {
                // ignore leading whitespace (that is not CR/LF)
                return VERSION;
            }
            if ((ver = tryFinishNonEmptyOnLF("HTTP-version")) != null) {
                return DONE;
            }
            if (isCR()) {
                // ignore. Will crash next iteration if CR isn't followed by LF.
                return VERSION;
            }
            if (isWhitespace()) {
                throw parseException("Whitespace in HTTP-version not accepted.");
            }
            consume();
            return VERSION;
        }
        
        @Override
        protected RequestLineParseException parseException(String msg) {
            return new RequestLineParseException(msg, prev, curr, getCount() - 1);
        }
    }
}