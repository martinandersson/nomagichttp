package alpha.nomagichttp.util;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpConstants.ReasonPhrase;
import alpha.nomagichttp.HttpConstants.StatusCode;

import java.util.function.BiFunction;
import java.util.function.IntFunction;

import static java.util.Arrays.stream;

/**
 * An immutable cache of objects derived from a status-code and/or
 * reason-phrase.<p>
 * 
 * The cache is stupidly fast. The cache is essentially just a
 * single-dimensional and sparse array of the cached values whose indices are
 * the status codes (after applied offset). It is hard to see how this could
 * have been made faster. Sparsity uses more memory, which is the price paid.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @param <T> type of cached thing
 */
public final class CodeAndPhraseCache<T>
{
    /**
     * Build a cache of things derived from all status codes and reason phrases
     * constants declared in the {@link HttpConstants} namespace.<p>
     * 
     * Each constant (only code) and pair (code + phrase) will be fed to the
     * given functions to construct the cached values; one value derived from
     * only the code and one derived from the pair.<p>
     * 
     * {@code null} values are technically allowed, albeit has no meaning and so
     * should not be produced.<p>
     * 
     * Cache entries never expire.
     * 
     * @param codeOnly value factory given only the code
     * @param codeAndPhrase value factory given code and phrase
     * @param <T> value type
     * @return the cache
     * @throws NullPointerException if any argument is {@code null}
     */
    public static <T> CodeAndPhraseCache<T> build(
            IntFunction<? extends T> codeOnly,
            BiFunction<Integer, String, ? extends T> codeAndPhrase)
    {
        @SuppressWarnings("rawtypes")
        Variants[] cache = new Variants[(MAX - MIN) + 1];
        for (int i = 0; i < StatusCode.VALUES.length; ++i) {
            int c = StatusCode.VALUES[i];
            var p = ReasonPhrase.VALUES[i];
            var v = new Variants<>(codeOnly.apply(c), codeAndPhrase.apply(c, p), p);
            cache[c - MIN] = v;
        }
        
        @SuppressWarnings({"rawtypes", "unchecked"})
        CodeAndPhraseCache<T> built = new CodeAndPhraseCache<>(cache);
        return built;
    }
    
    private static final int
            MIN = stream(StatusCode.VALUES).min().getAsInt(),
            MAX = stream(StatusCode.VALUES).max().getAsInt();
    
    private final Variants<T>[] cache;
    
    private CodeAndPhraseCache(Variants<T>[] cache) {
        this.cache = cache;
    }
    
    /**
     * Retrieve a value constructed from the given status-code.<p>
     * 
     * If the value does not exist, {@code null} is returned.
     * 
     * @param code of status
     * 
     * @return a value constructed from the given status-code
     */
    public T get(int code) {
        var v = get0(code);
        return v == null ? null : v.ofCode();
    }
    
    /**
     * Retrieve a value constructed from the given status-code and
     * reason-phrase (case-sensitive).<p>
     * 
     * If the value does not exist, {@code null} is returned.
     * 
     * @param code of status
     * @param phrase of reason
     * 
     * @return a value constructed from the given status-code and phrase
     * 
     * @throws NullPointerException
     *             if the entry based on status code is found and {@code phrase}
     *             is {@code null} (argument is not eagerly validated)
     */
    public T get(int code, String phrase) {
        var v = get0(code);
        return v == null || !phrase.equals(v.phraseUsed()) ?
                null : v.ofCodeAndPhrase();
    }
    
    private Variants<T> get0(int code) {
        try {
            return cache[code - MIN];
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }
    
    private record Variants<T>(T ofCode, T ofCodeAndPhrase, String phraseUsed) {
        // Empty
    }
}