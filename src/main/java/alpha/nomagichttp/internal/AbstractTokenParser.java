package alpha.nomagichttp.internal;

import java.util.function.Supplier;

import static java.lang.Byte.MIN_VALUE;
import static java.lang.System.Logger.Level.DEBUG;
import static java.util.Objects.requireNonNull;

/**
 * A partial implementation of a parser of token(s).<p>
 * 
 * This class does not contain parsing logic. It models a previous- and current
 * byte, a token builder, and some helpful methods on top of it all.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
abstract class AbstractTokenParser
{
    private static final System.Logger LOG
            = System.getLogger(AbstractTokenParser.class.getPackageName());
    
    private static final byte CR = 13, LF = 10;
    
    // Plz update before each byte processed
    protected byte curr = MIN_VALUE;
    
    // Plz update after each byte processed
    protected byte prev = MIN_VALUE;
    
    private final ReusableStringBuilder token = new ReusableStringBuilder();
    
    /**
     * Returns a parser-unique parse exception.
     * 
     * @param msg throwable message
     * @return see JavaDoc
     */
    protected abstract RuntimeException parseException(String msg);
    
    /**
     * Returns true if no bytes have been consumed and the current character is
     * whitespace.
     * 
     * @return see JavaDoc
     */
    final boolean isLeadingWhitespace() {
        return !hasConsumed() && isWhitespace();
    }
    
    /**
     * Returns true if the current character is carriage-return.
     * 
     * @return see JavaDoc
     */
    final boolean isCR() {
        return curr == CR;
    }
    
    /**
     * Returns true if the current character is not carriage-return.
     * 
     * @return see JavaDoc
     */
    final boolean isNotCR() {
        return curr != CR;
    }
    
    /**
     * Returns true if the current character is line-feed.
     * 
     * @return see JavaDoc
     */
    final boolean isLF() {
        return curr == LF;
    }
    
    /**
     * Returns true if the current character is not line-feed.
     * 
     * @return see JavaDoc
     */
    final boolean isNotLF() {
        return curr != LF;
    }
    
    /**
     * Returns true if the current character is a colon (':').
     * 
     * @return see JavaDoc
     */
    final boolean isColon() {
        return curr == ':';
    }
    
    /**
     * Returns true if the current character is whitespace.
     * 
     * @return see JavaDoc
     */
    final boolean isWhitespace() {
        return Character.isWhitespace(curr);
    }
    
    /**
     * Throws a parse exception if the current character is line-feed.
     */
    final void requireIsNotLF() {
        if (isLF()) {
            throw parseException("Unexpected LF.");
        }
    }
    
    /**
     * Append the current character to the active token.
     */
    final void consume() {
        token.append(curr);
    }
    
    /**
     * Append the given char sequence to the active token.
     * 
     * @param seq to append
     */
    final void consumeExplicit(CharSequence seq) {
        token.append(seq);
    }
    
    /**
     * Returns true if one or more chars have been appended to the active token.
     * 
     * @return see JavaDoc
     */
    final boolean hasConsumed() {
        return token.hasAppended();
    }
    
    /**
     * Build and return the active token.<p>
     * 
     * The returned token may be empty, but never {@code null}.
     * 
     * @return see JavaDoc
     */
    final String finish() {
        final var v = token.finish();
        assert v.isEmpty() || !Character.isWhitespace(v.charAt(0)) :
                "Token has leading whitespace";
        // There may be trailing whitespace, temporarily, for folded header values
        assert v.isEmpty() || !v.isBlank() : "Non-empty blank token";
        LOG.log(DEBUG, () -> "Parsed token \"" + escapeCRLF(v) + "\"");
        return v;
    }
    
     private static String escapeCRLF(String str) {
        // e.g. "bla\r\n\bla" becomes "bla{\r}{\n}bla"
        return str.replaceAll("\\r", "{\\\\r}")
                  .replaceAll("\\n", "{\\\\n}");
    }
    
    /**
     * Build and return the active token.<p>
     * 
     * The returned token is never empty and never {@code null}.
     * 
     * @param tokenName used as part of a parse exception message
     * @return see JavaDoc
     * @throws RuntimeException if the token is empty
     */
    final String finishNonEmpty(String tokenName) {
        final var v = finish();
        if (v.isEmpty()) {
            throw parseException("Empty " + requireNonNull(tokenName) + ".");
        }
        return v;
    }
    
    /**
     * Try {@link #finish()} on HTTP line termination.<p>
     * 
     * The line terminator is LF, optionally preceded by CR.<p>
     * 
     * If the previous character is CR followed by something else, an exception
     * is thrown (we expect CRLF or LF only).<p>
     * 
     * If the current char is not LF, {@code null} is returned.
     * 
     * @return see JavaDoc
     */
    final String tryFinishOnLF() {
        return tryFinishSomethingOnLF(this::finish);
    }
    
    /**
     * Equivalent to {@link #tryFinishOnLF()}, except the finalizer used is
     * {@link #finishNonEmpty(String)}.
     * 
     * @param tokenName used as part of a parse exception message
     * @return see {@link #finishNonEmpty(String)}
     */
    final String tryFinishNonEmptyOnLF(String tokenName) {
        return tryFinishSomethingOnLF(() -> finishNonEmpty(tokenName));
    }
    
    private String tryFinishSomethingOnLF(Supplier<String> what) {
        if (prev == CR) {
            if (isLF()) {
                // CRLF; the "correct" HTTP line ending.
                return what.get();
            }
            else {
                throw parseException("CR followed by something other than LF.");
            }
        }
        else if (isLF()) {
            // Didn't receive a preceding CR, but that's okay.
            return what.get();
        }
        return null;
    }
}