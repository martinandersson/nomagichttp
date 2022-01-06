package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.EndOfStreamException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.RawRequestLine;
import alpha.nomagichttp.message.RequestLineParseException;

import static java.lang.System.Logger.Level.DEBUG;

/**
 * Parse bytes into a {@link RawRequestLine}.<p>
 * 
 * If the upstream (ChannelByteBufferPublisher) terminates the subscription with
 * an {@link EndOfStreamException} <i>and</i> no bytes have been received by
 * this subscriber, then the result-stage will complete exceptionally with a
 * {@link ClientAbortedException}.<p>
 * 
 * Exceptions that originate from the channel will already have closed the
 * read stream. Any exception that originates from this class will close the
 * read stream; message framing lost.<p>
 * 
 * On parse error, the stage will complete with a {@link
 * RequestLineParseException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Document, impl, and test various forms for request-target?
//       https://datatracker.ietf.org/doc/html/rfc7230#section-5.3
final class RequestLineSubscriber extends AbstractByteSubscriber<RawRequestLine>
{
    private static final System.Logger LOG
            = System.getLogger(RequestLineSubscriber.class.getPackageName());
    
    private final int maxBytes;
    private final ClientChannel chApi;
    private final Parser parser;
    private long started;
    
    /**
     * Initializes this object.
     * 
     * If {@code maxRequestHeadSize} is exceeded while parsing, the result stage
     * will complete exceptionally with a {@link
     * MaxRequestHeadSizeExceededException}.
     * 
     * @param maxRequestHeadSize max bytes to parse
     * @param chApi used for exceptional shutdown of read stream
     */
    RequestLineSubscriber(int maxRequestHeadSize, ClientChannel chApi) {
        this.maxBytes = maxRequestHeadSize;
        this.chApi    = chApi;
        assert chApi != null;
        this.parser   = new Parser();
    }
    
    @Override
    protected RawRequestLine parse(byte b) {
        int r = read();
        if (r == maxBytes) {
            throw new MaxRequestHeadSizeExceededException(); }
        if (r == 1) {
            started = System.nanoTime(); }
        try {
            return parser.parse(b);
        } catch (Throwable t) {
            if (chApi.isOpenForReading()) {
                LOG.log(DEBUG,
                    "Request-line parsing failed, " +
                    "shutting down the channel's read stream.");
                // TODO: Delete chApi from this class and HeadersSubscriber.
                //       A downstream decorator/monitor or even ErrorHandler(?) must close.
                chApi.shutdownInputSafe();
            }
            throw t;
        }
    }
    
    @Override
    public void onError(Throwable t) {
        if (t instanceof EndOfStreamException && read() == 0) {
            t = new ClientAbortedException(t);
        }
        super.onError(t);
    }
    
    private static final int
            METHOD = 0, TARGET = 1, VERSION = 2, DONE = 3;
    
    private final class Parser extends AbstractTokenParser {
        private int parsing = METHOD;
        
        RawRequestLine parse(byte b) {
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
                    new RawRequestLine(method, rt, ver, started, read());
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
        
        protected RequestLineParseException parseException(String msg) {
            return new RequestLineParseException(msg, prev, curr, read() - 1);
        }
    }
}