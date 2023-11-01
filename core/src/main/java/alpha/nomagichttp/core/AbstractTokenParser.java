package alpha.nomagichttp.core;

import java.util.function.Function;
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
    
    private final Function<String, RuntimeException> parseExc;
    
    protected AbstractTokenParser(Function<String, RuntimeException> parseExc) {
        this.parseExc = parseExc;
    }
    
    private byte curr = MIN_VALUE;
    
    /**
     * Sets the current byte to be processed.<p>
     * 
     * The concrete parser must call this method before processing the byte.
     * 
     * @param b byte to process
     */
    protected final void current(byte b) {
        this.curr = b;
    }
    
    /**
     * {@return the current byte being processed}
     */
    final byte current() {
        return curr;
    }
    
    /**
     * {@return {@code true} if no bytes have been consumed and the current
     * character is whitespace}
     */
    final boolean isLeadingWhitespace() {
        return !hasConsumed() && isWhitespace();
    }
    
    private byte prev = MIN_VALUE;
    
    /**
     * Sets the previous byte processed.<p>
     * 
     * The concrete parser must call this method after each byte processed.
     * 
     * @param b byte previously processed
     */ 
    // TODO: Can be side-effect, no need to set explicitly?
    protected final void previous(byte b) {
        this.prev = b;
        current(MIN_VALUE);
    }
    
    /**
     * {@return the previous byte processed}
     */
    final byte previous() {
        return prev;
    }
    
    private final ReusableStringBuilder token = new ReusableStringBuilder();
    
    private static final byte CR = 13, LF = 10;
    
    /**
     * {@return {@code true} if the current character is carriage-return}
     */
    final boolean isCR() {
        return curr == CR;
    }
    
    /**
     * {@return {@code true} if the current character is not carriage-return}
     */
    final boolean isNotCR() {
        return curr != CR;
    }
    
    /**
     * {@return {@code true} if the current character is line-feed}
     */
    final boolean isLF() {
        return curr == LF;
    }
    
    /**
     * {@return {@code true} if the current character is not line-feed}
     */
    final boolean isNotLF() {
        return curr != LF;
    }
    
    /**
     * {@return {@code true} if the current character is a colon (':')}
     */
    final boolean isColon() {
        return curr == ':';
    }
    
    /**
     * {@return {@code true} if the current character is whitespace}
     */
    final boolean isWhitespace() {
        return Character.isWhitespace(curr);
    }
    
    /**
     * Throws a parse exception if the current character is line-feed.
     */
    final void requireIsNotLF() {
        if (isLF()) {
            throw parseExc.apply("Unexpected LF.");
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
     * {@return {@code true} if one or more chars have been appended to the
     * active token}
     */
    final boolean hasConsumed() {
        return token.hasAppended();
    }
    
    /**
     * Build and return the active token.<p>
     * 
     * The returned token may be empty, but never {@code null}.
     * 
     * @return the built token
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
     * @return the built token
     * @throws RuntimeException if the token is empty
     */
    final String finishNonEmpty(String tokenName) {
        final var v = finish();
        if (v.isEmpty()) {
            throw parseExc.apply("Empty " + requireNonNull(tokenName) + ".");
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
     * @return the built token, exceptionally or {@code null}
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
                throw parseExc.apply("CR followed by something other than LF.");
            }
        }
        else if (isLF()) {
            // Didn't receive a preceding CR, but that's okay.
            return what.get();
        }
        return null;
    }
}