package alpha.nomagichttp.message;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.RandomAccess;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_TYPE;
import static alpha.nomagichttp.HttpConstants.HeaderName.TRANSFER_ENCODING;
import static alpha.nomagichttp.message.MediaType.parse;
import static alpha.nomagichttp.util.Strings.containsIgnoreCase;
import static alpha.nomagichttp.util.Strings.requireNoSurroundingWS;
import static alpha.nomagichttp.util.Strings.splitToSink;
import static java.lang.Long.parseLong;
import static java.lang.String.CASE_INSENSITIVE_ORDER;
import static java.text.MessageFormat.format;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toCollection;

/**
 * Default implementation of {@link ContentHeaders}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class DefaultContentHeaders implements ContentHeaders
{
    private static final DefaultContentHeaders EMPTY
            = new DefaultContentHeaders(new LinkedHashMap<>(), false);
    
    /**
     * Returns an empty {@code DefaultContentHeaders} (singleton).
     * 
     * @return see JavaDoc
     */
    public static DefaultContentHeaders empty() {
        return EMPTY;
    }
    
    /** Saved iteration order. */
    private final String[] keys;
    /** Case-insensitive. Modifiable; don't leak. */
    private final Map<String, List<String>> lookup;
    
    /**
     * Constructs this object.
     * 
     * @param entries multivalued {@code Map} of headers
     * @param stripTrailing whether to strip trailing whitespace from values
     * 
     * @throws NullPointerException
     *             if {@code entries} is {@code null}
     * @throws IllegalArgumentException
     *             if a header name is repeated using different casing
     */
    public DefaultContentHeaders(
            LinkedHashMap<String, List<String>> entries, boolean stripTrailing)
    {
        var len = entries.size();
        var keys = new String[len];
        var idx  = new int[1];
        var lookup = new TreeMap<String, List<String>>(CASE_INSENSITIVE_ORDER);
        entries.forEach((k, v) -> {
            if (lookup.put(k, copyOf(v, stripTrailing)) != null) {
                throw new IllegalArgumentException(
                        "Header name repeated with different casing: " + k);
            }
            keys[idx[0]++] = k;
        });
        this.keys = keys;
        this.lookup = lookup;
    }
    
    private static List<String> copyOf(
            List<String> values, boolean stripTrailing)
    {
        if (stripTrailing) {
            var l = values.stream().map(v -> {
                assert v.isEmpty() || !Character.isWhitespace(v.charAt(0)) :
                        "Parser shouldn't have accepted leading whitespace";
                return v.stripTrailing();
            }).toList();
            assert l instanceof RandomAccess;
            return l;
        }
        return List.copyOf(values);
    }
    
    
    // ----------------------
    //   BETTER HEADERS API
    // ----------------------
    
    @Override
    public final boolean contains(String headerName) {
        requireNoSurroundingWS(headerName);
        return lookup.containsKey(headerName);
    }
    
    @Override
    public final boolean contains(String headerName, String valueSubstring) {
        requireNonNull(valueSubstring);
        return allValues(requireNoSurroundingWS(headerName))
              .stream()
              .anyMatch(v -> containsIgnoreCase(v, valueSubstring));
        
    }
    
    private byte hcc = -1;
    
    @Override
    public final boolean hasConnectionClose() {
        if (hcc != -1) {
            return hcc == 1;
        }
        if (contains(CONNECTION, "close")) {
            hcc = 1;
            return true;
        }
        hcc = 0;
        return false;
    }
    
    private byte htec = -1;
    
    @Override
    public final boolean hasTransferEncodingChunked() {
        if (htec != -1) {
            return htec == 1;
        }
        if (contains(TRANSFER_ENCODING, "chunked")) {
            htec = 1;
            return true;
        }
        htec = 0;
        return false;
    }
    
    @Override
    public final boolean isMissingOrEmpty(String headerName) {
        // allMatch returns true if the stream is empty
        return allValues(headerName).stream().allMatch(String::isEmpty);
    }
    
    @Override
    public final Optional<String> firstValue(String headerName) {
        var values = allValues(headerName);
        return values.isEmpty() ?
                Optional.empty() : Optional.of(values.get(0));
    }
    
    @Override
    public OptionalLong firstValueAsLong(String headerName) {
        var values = allValues(headerName);
        return values.isEmpty() ?
                OptionalLong.empty() : OptionalLong.of(parseLong(values.get(0)));
    }
    
    @Override
    public final List<String> allValues(String headerName) {
        requireNoSurroundingWS(headerName);
        return lookup.getOrDefault(headerName, List.of());
    }
    
    @Override
    public final Stream<String> allTokens(String headerName) {
        return allTokens0(headerName, false);
    }
    
    @Override
    public final Stream<String> allTokensKeepQuotes(String headerName) {
        return allTokens0(headerName, true);
    }
    
    private Stream<String> allTokens0(String headerName, boolean keepQuotes) {
        // NPE not documented in JDK
        return allValues(headerName)
              .stream()
              .<String>mapMulti((line, sink) -> {
                  if (keepQuotes) splitToSink(line, ',', '"', sink);
                  else            splitToSink(line, ',',      sink);})
              .map(String::strip)
              .filter(not(String::isEmpty));
    }
    
    @Override
    public final void forEach(BiConsumer<String, List<String>> action) {
        if (keys.length == 0) {
            requireNonNull(action);
        }
        for (String k : keys) {
            action.accept(k, lookup.get(k));
        }
    }
    
    @Override
    public Iterator<Map.Entry<String, List<String>>> iterator() {
        return new IteratorImpl();
    }
    
    @Override
    public final String toString() {
        return lookup.toString();
    }
    
    class IteratorImpl implements Iterator<Map.Entry<String, List<String>>> {
        private int idx = 0;
        
        @Override
        public boolean hasNext() {
            return idx < keys.length;
        }
        
        @Override
        public Map.Entry<String, List<String>> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            var k = keys[idx++];
            var v = lookup.get(k);
            return Map.entry(k, v);
        }
    }
    
    
    // -----------------------
    //   CONTENT HEADERS API
    // -----------------------
    
    private Optional<MediaType> cc;
    
    @Override
    public final Optional<MediaType> contentType() {
        var cc = this.cc;
        return cc != null ? cc : (this.cc = mkContentType());
    }
    
    private Optional<MediaType> mkContentType() {
        final var tkns = allTokens(CONTENT_TYPE).toArray(String[]::new);
        if (tkns.length == 0) {
            return Optional.empty();
        }
        if (tkns.length > 1) {
            throw new BadHeaderException(
                "Multiple " + CONTENT_TYPE + " values in request.");
        }
        try {
            return Optional.of(parse(tkns[0]));
        } catch (MediaTypeParseException e) {
            throw new BadHeaderException(
                "Failed to parse " + CONTENT_TYPE + " header.", e);
        }
    }
    
    private OptionalLong cl;
    
    @Override
    public final OptionalLong contentLength() {
        var cl = this.cl;
        return cl != null ? cl : (this.cl = mkContentLength());
    }
    
    private OptionalLong mkContentLength() {
        final var tkns = allTokens(CONTENT_LENGTH).toArray(String[]::new);
        if (tkns.length == 0) {
            return OptionalLong.empty();
        }
        if (tkns.length > 1) {
            throw new BadHeaderException(
                "Multiple " + CONTENT_LENGTH + " values in request.");
        }
        final long v;
        try {
            v = parseLong(tkns[0]);
        } catch (NumberFormatException e) {
            var msg = format(
                "Can not parse {0} (\"{1}\") into a long.",
                CONTENT_LENGTH, tkns[0]);
            throw new BadHeaderException(msg, e);
        }
        if (v < 0) {
            throw new BadHeaderException(format(
                "{0} is negative ({1})", CONTENT_LENGTH, v));
        }
        return OptionalLong.of(v);
    }
    
    @Override
    public final Deque<String> transferEncoding() {
        var te = allTokens(TRANSFER_ENCODING)
                     .collect(toCollection(ArrayDeque::new));
        if (!te.isEmpty() && !"chunked".equalsIgnoreCase(te.getLast())) {
            throw new BadHeaderException(format(
                "Last {0} token (\"{1}\") is not \"chunked\".",
                TRANSFER_ENCODING, te.getLast()));
        }
        return te;
    }
}