package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.RequestHeadParseException;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.Character.isWhitespace;
import static java.lang.System.Logger.Level.DEBUG;
import static java.util.Objects.requireNonNull;

/**
 * Processes bytes into a request head.<p>
 * 
 * When left with a choice, this processor follows rather a lenient model than a
 * strict one.
 * 
 * 
 * <h2>General rules</h2>
 * 
 * Citation from <a href="https://tools.ietf.org/html/rfc7230#section-3.5">RFC
 * 7230 §3.5</a>
 * 
 * <blockquote>
 *     Although the line terminator for the start-line and header fields is the
 *     sequence CRLF, a recipient MAY recognize a single LF as a line terminator
 *     and ignore any preceding CR.
 * </blockquote>
 * 
 * This processor ignores CR immediately preceding LF; it is never consumed as
 * part of the request. If anything else follows the CR an exception is thrown,
 * except for cases where it is either considered as a word boundary
 * (start-line) or ignored as leading whitespace.
 * 
 * 
 * <h2>Request-line rules</h2>
 *
 * Citation from <a href="https://tools.ietf.org/html/rfc7230#section-3.5">RFC
 * 7230 §3.5</a>
 * 
 * <blockquote>
 *     Although the request-line and status-line grammar rules require that each
 *     of the component elements be separated by a single SP octet, recipients
 *     MAY instead parse on whitespace-delimited word boundaries and, aside from
 *     the CRLF terminator, treat any form of whitespace as the SP separator
 *     while ignoring preceding or trailing whitespace; such whitespace includes
 *     one or more of the following octets: SP, HTAB, VT (%x0B), FF (%x0C), or
 *     bare CR.
 * </blockquote>
 * 
 * This processor follows the "MAY" part.
 * 
 * 
 * <h2>Header names</h2>
 *
 * Citation from <a href="https://tools.ietf.org/html/rfc7230#section-3.2.4">RFC
 * 7230 §3.2.4</a>
 * 
 * <blockquote>
 *     No whitespace is allowed between the header field-name and colon.
 * </blockquote>
 * 
 * One could argue semantically that whitespace is not allowed inside the
 * header name (what would make or not make the whitespace before the colon be a
 * part of a legal field name?). I haven't found anything conclusive for or
 * against whitespace in header names. It does, however, seem to be extremely
 * uncommon and will complicate the processor logic. It further appears to be
 * banned in HTTP/2 and other projects (with zero documentation and exception
 * handling) will crash [unexpectedly] when processing whitespace in header
 * names. Netty 4.1.48 even hangs indefinitely.<p>
 * 
 * This processor will not allow whitespace in header names.<p>
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
 * Line folding is deprecated for all header values except media types. Why? I
 * do not know. What I do know is that allowing line folding for some but not
 * all is obviously complicating things. This processor will allow it for all
 * headers.<p>
 * 
 * References: <br>
 * https://github.com/eclipse/jetty.project/issues/1116 <br>
 * https://stackoverflow.com/a/31324422/1268003
 * 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Document, impl, and test various forms for request-target?
//       https://datatracker.ietf.org/doc/html/rfc7230#section-5.3
final class RequestHeadProcessor
{
    private static final System.Logger LOG = System.getLogger(RequestHeadProcessor.class.getPackageName());
    
    private static final byte CR = 13, LF = 10;
    
    private final StringBuilder token;
    
    private int read;
    private char prev, curr;
    
    private Runnable step;
    private RequestHead completed;
    
    RequestHeadProcessor() {
        token = new StringBuilder();
        read = 0;
        prev = curr = Character.MIN_VALUE;
        step = this::parseMethod;
        completed = null;
    }
    
    RequestHead accept(char curr) {
        ++read;
        this.curr = curr;
        step.run();
        prev = curr;
        return completed;
    }
    
    /**
     * Returns {@code true} if at least one byte has been processed, otherwise
     * {@code false}.
     * 
     * @return {@code true} if at least one byte has been processed, otherwise
     * {@code false}
     */
    boolean hasStarted() {
        return read > 0;
    }
    
    private String method;
    
    private void parseMethod() {
        if (isLeadingWhitespace()) {
            // ignore all leading whitespace (including CR/LF)
            return; }
        
        unexpected(LF);
        
        if (isWhitespace(curr)) {
            // any whitespace, including CR terminates this token
            method = finishThen(this::parseRequestTarget, false, "method");
        } else {
            consume();
        }
    }
    
    private String requestTarget;
    
    private void parseRequestTarget() {
        // always crash on LF
        unexpected(LF);
        
        if (isLeadingWhitespace()) {
            // ignore all leading whitespace (including CR)
            return; }
        
        if (isWhitespace(curr)) {
            requestTarget = finishThen(this::parseHttpVersion, false, "request-target");
        } else {
            consume();
        }
    }
    
    private String httpVersion;
    
    private void parseHttpVersion() {
        if (isLeadingWhitespace() && curr != CR && curr != LF) {
            // ignore leading whitespace that is not CR/LF
            return;
        }
        
        if ((httpVersion = finishOnLFThen(this::parseHeaderKey, false, "HTTP-version")) != null) {
            // done
            return;
        }
        
        if (curr == CR) {
            // ignore. Will crash next iteration if CR isn't followed by LF.
            return;
        }
        else if (isWhitespace(curr)) {
            throw parseException("Whitespace in HTTP-version not accepted.");
        }
        
        consume();
    }
    
    private String headerKey;
    
    private void parseHeaderKey() {
        if (isLeadingWhitespace() && curr != CR && curr != LF) {
            if (headerValues == null) {
                throw parseException("Leading whitespace in header key not accepted."); }
            
            LOG.log(DEBUG, "Resuming last [folded] header value.");
            step = this::parseHeaderValueFolded;
            return;
        }
        
        if (curr == CR) {
            // Ignore. Will crash next iteration if CR isn't followed by LF.
            return;
        }
        else if (curr == LF) {
            if (hasConsumed()) {
                throw parseException("Whitespace in header key or before colon is not accepted.");
            }
            complete();
        }
        else if (isWhitespace(curr)) {
            throw parseException("Whitespace in header key or before colon is not accepted.");
        }
        else if (curr == ':') {
            headerKey = finishThen(this::parseHeaderValueNew, false, "header key");
        }
        else {
            consume();
        }
    }
    
    private LinkedHashMap<String, List<String>> headerValues;
    
    // TODO: Document this accepts trailing whitespace
    private void parseHeaderValueNew() {
        // accept empty token; so that the folding processor knows what List to continue
        String hv = finishOnLFThen(this::parseHeaderKey);
        
        if (hv == null) {
            if (isLeadingWhitespace()) {
                // ignore all leading whitespace
            } else {
                consume();
            }
        }
        else {
            if (headerValues == null) {
                headerValues = new LinkedHashMap<>();
            }
            headerValues.computeIfAbsent(headerKey, k -> new ArrayList<>(1)).add(hv);
        }
    }
    
    private void parseHeaderValueFolded() {
        if (curr == LF) {
            LOG.log(DEBUG, "Unexpected LF when anticipating a folded header value. But, we forgive and complete the head.");
            complete();
        } else if (!isWhitespace(curr)) {
            // Restore header value and manually call the "standard" processor
            List<String> values = headerValues.get(headerKey);
            String last = values.remove(values.size() - 1);
            
            if (!last.isEmpty()) {
                boolean endsWithWS = isWhitespace(last.charAt(last.length() - 1));
                token.append(endsWithWS ? last : last + ' ');
            }
            
            step = this::parseHeaderValueNew;
            step.run();
        }
        // else ignore all leading whitespace
    }
    
    private boolean isLeadingWhitespace() {
        return !hasConsumed() && isWhitespace(curr);
    }
    
    private void consume() {
        token.append(curr);
    }
    
    private boolean hasConsumed() {
        return token.length() > 0;
    }
    
    private String finishOnLFThen(Runnable next) {
        return finishOnLFThen(next, true, null);
    }
    
    private String finishOnLFThen(Runnable next, boolean acceptEmptyToken, String tokenName) {
        if (prev == CR) {
            if (curr == LF) {
                // CRLF; the "correct" HTTP line ending.
                return finishThen(next, acceptEmptyToken, tokenName);
            }
            else {
                throw parseException("CR followed by something other than LF.");
            }
        }
        else if (curr == LF) {
            // Didn't receive a preceding CR, but that's okay.
            return finishThen(next, acceptEmptyToken, tokenName);
        }
        
        return null;
    }
    
    private String finishThen(Runnable next, boolean acceptEmptyToken, String tokenName) {
        final String val = token.toString();
        LOG.log(DEBUG, () -> "Finished token: \"" + val + "\".");
        
        if (!acceptEmptyToken && val.isEmpty()) {
            throw parseException("Empty " + requireNonNull(tokenName) + ".");
        }
        
        token.setLength(0);
        step = next;
        return val;
    }
    
    private void unexpected(byte bad) {
        if (curr == bad) {
            throw parseException("Unexpected char.");
        }
    }
    
    private RuntimeException parseException(String msg) {
        return new RequestHeadParseException(msg, prev, curr, read - 1);
    }
    
    private void complete() {
        HttpHeaders headers = HttpHeaders.of(
                headerValues != null ? headerValues : Map.of(),
                (k, v) -> true);
        
        completed = new RequestHead(
                method, requestTarget, httpVersion, headers);
    }
}