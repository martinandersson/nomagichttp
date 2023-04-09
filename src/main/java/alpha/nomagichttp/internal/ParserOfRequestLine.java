package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.MaxRequestHeadSizeException;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.RequestLineParseException;

/**
 * A parser of {@code RawRequest.Line}.<p>
 * 
 * When left with a choice, this parser follows a lenient model rather than a
 * strict one.
 * 
 * <h2>General rules</h2>
 * 
 * Citation from
 * <a href="https://tools.ietf.org/html/rfc7230#section-3.5">RFC 7230 §3.5</a>:
 *
 * <blockquote>
 *     Although the line terminator for the start-line and header fields is
 *     the sequence CRLF, a recipient MAY recognize a single LF as a line
 *     terminator and ignore any preceding CR.
 * </blockquote>
 * 
 * This parser ignores CR immediately preceding LF; it is never consumed as part
 * of the request. If anything else follows the CR an exception is thrown,
 * except for cases where it is either considered as a word boundary
 * (start-line) or ignored as leading whitespace.
 * 
 * <h2>Request-line rules</h2>
 *
 * Citation from
 * <a href="https://tools.ietf.org/html/rfc7230#section-3.5">RFC 7230 §3.5</a>:
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
// TODO: Document, impl, and test various forms for request-target?
//       https://datatracker.ietf.org/doc/html/rfc7230#section-5.3
final class ParserOfRequestLine extends AbstractResultParser<RawRequest.Line>
{
    private final int maxBytes;
    private final TokenParser parser;
    private long started;
    
    /**
     * Constructs this object.
     * 
     * @param in byte source
     * @param maxRequestHeadSize max bytes to parse
     */
    // TODO: Use httpServer().getConfig() instead of argument
    ParserOfRequestLine(ByteBufferIterable in, int maxRequestHeadSize) {
        super(in);
        maxBytes = maxRequestHeadSize;
        parser = new TokenParser();
    }
    
    @Override
    protected RawRequest.Line tryParse(byte b)
          throws RequestLineParseException, MaxRequestHeadSizeException
    {
        final int r = byteCount();
        if (r == maxBytes) {
            throw new MaxRequestHeadSizeException();
        }
        if (r == 1) {
            started = System.nanoTime();
        }
        return parser.tryParse(b);
    }
    
    @Override
    protected RuntimeException parseException(String msg) {
        return new RequestLineParseException(
                msg, parser.previous(), parser.current(), position(), byteCount());
    }
    
    private static final int
            METHOD = 0, TARGET = 1, VERSION = 2, DONE = 3;
    
    private final class TokenParser extends AbstractTokenParser {
        TokenParser() {
            super(ParserOfRequestLine.this::parseException);
        }
        
        private int parsing = METHOD;
        
        RawRequest.Line tryParse(byte b) {
            current(b);
            parsing = switch (parsing) {
                case METHOD  -> parseMethod();
                case TARGET  -> parseTarget();
                case VERSION -> parseVersion();
                default ->
                    throw new AssertionError("Not parsing anything lol.");
            };
            previous(b);
            return parsing != DONE ? null :
                    new RawRequest.Line(method, rt, ver, started, byteCount());
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
    }
}