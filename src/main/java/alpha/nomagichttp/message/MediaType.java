package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.route.Route;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static alpha.nomagichttp.message.MediaType.Score.NOPE;
import static alpha.nomagichttp.message.MediaType.Score.PERFECT;
import static alpha.nomagichttp.message.MediaType.Score.WORKS;
import static java.lang.Double.parseDouble;
import static java.lang.System.Logger;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.joining;

/**
 * A media type is an identifier for content being transmitted; in HTTP parlance
 * also known as the "representation" or "type of representation".<p>
 * 
 * Most commonly used as the value in a "Content-Type: " header to identify what
 * type of entity is contained in the request or response body.<p>
 * 
 * For example; "application/octet-stream" or "text/html; charset=utf-8". The
 * whole list is available on
 * <a href="https://www.iana.org/assignments/media-types/media-types.xhtml">
 * IANA:s website</a>.<p>
 * 
 * {@code MediaType} as well as the specialized {@link MediaRange} can be used
 * in an "Accept: " header to drive client-initiated and "proactive content
 * negotiation".<p>
 * 
 * On the server-side, both {@code MediaType} and {@code MediaRange} can be used
 * to specify what a {@code Handler} consumes and/or produces, in order to
 * participate in the handler selection process. Please read the javadoc of
 * {@link Handler} to learn more on that.<p>
 * 
 * Many commonly used media types are available as public static constants in
 * this class. Missing one? Let us know!<p>
 * 
 * 
 * <h2>Type and subtype</h2>
 * 
 * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types">Mozilla</a>
 * explains this well:
 * <blockquote>
 *  The type represents the general category into which the data type falls
 *  such as video or text. The subtype identifies the exact kind of data of the
 *  specified type the MIME type represents. For example, for the MIME type text,
 *  the subtype might be plain (plain text), html (HTML source code), or
 *  calendar (for iCalendar/.ics) files.
 * </blockquote>
 * 
 * Please note that some subtypes contains a "+" character followed by what is
 * known as a "suffix". For example "application/vnd.hal+json". This suffix is
 * part of the subtype string and is not treated any differently.
 * 
 * 
 * <h2>Media type parameters</h2>
 * 
 * A media type can have "companion data" (
 * <a href="https://tools.ietf.org/html/rfc6838#section-1">RFC 6838</a>) in the
 * form of parameters. The meaning of these are specific for each media type.<p>
 * 
 * In application code they can be used for whatever purpose fits the
 * application's needs. For example, this String defines two parameters
 * ("version" and "structure"):
 * "application/vnd.example; version=2; structure=json".<p>
 * 
 * In reality, it's probably a bit easier to both understand and implement
 * media type logic if the application can skip the parameters. The previous
 * example could be rewritten as "application/vnd.example.v2+json". This example
 * could be even more simplified if the major version is moved to the resource
 * URI.<p>
 * 
 * This class as well as the server implementation does not treat parameters any
 * differently whether they are or are not attached to a media range specified
 * using a wildcard (*).
 * 
 * 
 * <h2>Thread-safety and identity.</h2>
 * 
 * {@code MediaType} is an immutable value-based class.<p>
 * 
 * Parameters are relevant for object equality but the order of parameters are
 * not. The casing of parameter names are irrelevant, but parameter value casing
 * is relevant for all but the value of the "charset" parameter of "text/*".
 * 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class MediaType
{
    private static final Logger LOG = System.getLogger(MediaType.class.getPackageName());
    private static final String WILDCARD = "*";
    private static final Pattern SEMICOLON = Pattern.compile(";");
    
    /**
     * A sentinel media type that can optionally be used as a handler's
     * consuming media type to indicate that the handler consumes nothing and is
     * not willing to handle requests that has the "Content-Type" header set.<p>
     * 
     * <strong>Not</strong> valid as a handler's producing media type.
     * 
     * @see Handler
     */
    public static final MediaType NOTHING
            = new MediaType("<nothing>", null, null, Map.of()) {};
    
    /**
     * A sentinel media type that can optionally be used as a handler's
     * consuming media type to indicate that the handler do not care about the
     * request header's "Content-Type"; it can be missing as well as it can be
     * any media type.<p>
     * 
     * <strong>Not</strong> valid as a handler's producing media type.
     * 
     * @see Handler
     */
    public static final MediaType NOTHING_AND_ALL
            = new MediaType("<nothing and all>", null, null, Map.of()) {};
    
    /**
     * A sentinel media type that can optionally be used as a handler's
     * consuming and/or producing media type to indicate that the handler is
     * able to consume and/or produce any media type.<p>
     * 
     * A handler that declares this media type as producing is still free to not
     * respond a message body.
     * 
     * @see Handler
     */
    public static final MediaType ALL = parse("*/*");
    
    /**
     * Media type "text/plain".
     */
    public static final MediaType TEXT_PLAIN = parse("text/plain");
    
    /**
     * Parse a text into a {@link MediaType} or a {@link MediaRange}.<p>
     * 
     * If the parsed <i>type</i> or <i>subtype</i> is a wildcard character ("*")
     * or the last parameter (if present) is a "q"/"Q"-named parameter, then the
     * returned type is a {@code MediaRange}, otherwise a {@code MediaType}.<p>
     * 
     * The type, subtype and parameter names are all case-insensitive. They will
     * be lower cased.<p>
     * 
     * No meaning is attached to the order in which parameters appear - except
     * for the "q"/"Q" parameter which - if present - marks the end of media
     * type parameters.<p>
     * 
     * There is no defined syntax for parameter values. Almost all parameter
     * values will be extracted at face value, except for quoted strings which
     * will be unquoted ("blabla" -> blabla).<p>
     * 
     * The only parameter value that will be lower cased is the "charset"
     * parameter for all "text/*" media types. I.e., treated case-insensitively
     * when routing a request to a handler (
     * <a href="https://tools.ietf.org/html/rfc2046#section-4.1.2">
     * RFC 2046 §4.1.2</a>).<p>
     * 
     * All tokens extracted from the specified {@code mediaType} will be
     * stripped of leading and trailing white space.<p>
     * 
     * As an example, all these are considered equal:
     * <pre>
     *   text/html;charset=utf-8
     *   text/html;charset=UTF-8
     *   Text/HTML;Charset="utf-8"
     *   text/html; charset="utf-8"
     * </pre>
     * 
     * Media range {@link MediaRange extension parameters} are logged and
     * subsequently ignored.
     * 
     * @param text to parse
     * 
     * @throws NullPointerException
     *             if {@code mediaType} is {@code null}
     * 
     * @throws BadMediaTypeSyntaxException
     *             if the specified {@code mediaType} can not be parsed, for example
     *             a parameter has been specified more than once, or
     *             a parameter was named "q"/"Q" and it was not the last parameter declared,
     *             and so forth
     */
    public static MediaType parse(final CharSequence text) {
        // First part is type/subtype, possibly followed by ;params
        final String[] tokens = SEMICOLON.split(text);
        
        String[] types = parseTypes(tokens[0], text);
        
        final String type = types[0],
                  subtype = types[1];
        
        Map<String, String> params = parseParams(type, tokens, 1, true, text);
        final int size = params.size();
        
        String qStr = params.remove("q");
        OptionalDouble qVal = OptionalDouble.empty();
        
        if (qStr != null) {
            try {
                qVal = OptionalDouble.of(parseDouble(qStr));
            }
            catch (NumberFormatException e) {
                throw new BadMediaTypeSyntaxException(text,
                        "Non-parsable value for q-parameter.", e);
            }
        }
        
        Map<String, String> extension = parseParams(type, tokens, 1 + size, false, text);
        
        if (!extension.isEmpty()) {
            LOG.log(WARNING, () -> "Media type extension parameters ignored: " + extension);
            // TODO: Remove this once I have test for the logging statement
            throw new UnsupportedOperationException(
                    "Need to log these extension params: " + extension);
        }
        
        if (type.equals(WILDCARD) || subtype.equals(WILDCARD) || qVal.isPresent()) {
            return new MediaRange(text.toString(), type, subtype, params, qVal.orElse(1));
        }
        
        return new MediaType(text.toString(), type, subtype, params);
    }
    
    private static String[] parseTypes(String token, CharSequence text) {
        final String[] raw = token.split("/");
        
        if (raw.length != 2) {
            throw new BadMediaTypeSyntaxException(text,
                    "Expected only one forward slash in <type/subtype>.");
        }
        
        final String type = stripAndLowerCase(raw[0]);
        
        if (type.isEmpty()) {
            throw new BadMediaTypeSyntaxException(text, "Type is empty.");
        }
        
        final String subtype = stripAndLowerCase(raw[1]);
        
        if (subtype.isEmpty()) {
            throw new BadMediaTypeSyntaxException(text, "Subtype is empty.");
        }
        
        if (type.equals(WILDCARD) && !subtype.equals(WILDCARD)) {
            throw new BadMediaTypeSyntaxException(text,
                    "Wildcard type but not a wildcard subtype.");
        }
        
        return type == raw[0] && subtype == raw[1] ? raw :
                new String[]{ type, subtype };
    }
    
    private static Map<String, String> parseParams(
            String type, String[] tokens, int offset, boolean stopAfterQ, CharSequence text)
    {
        Map<String, String> params = Collections.emptyMap();
        
        for (int i = offset; i < tokens.length; ++i) {
            String[] nameAndValue = parseParam(type, tokens[i], text);
            
            if (params.isEmpty()) {
                params = new LinkedHashMap<>();
            }
            
            if (params.put(nameAndValue[0], nameAndValue[1]) != null) {
                throw new BadMediaTypeSyntaxException(text, "Duplicated parameters.");
            }
            
            if (stopAfterQ && nameAndValue[0].equals("q")) {
                break;
            }
        }
        
        return params;
    }
    
    private static String[] parseParam(String type, String token, CharSequence text) {
        int eq = token.indexOf("=");
        
        if (eq == -1) {
            throw new BadMediaTypeSyntaxException(text, "A parameter has no assigned value.");
        }
        
        String name = stripAndLowerCase(token.substring(0, eq));
        
        if (name.isEmpty()) {
            throw new BadMediaTypeSyntaxException(text, "Empty parameter name.");
        }
        
        String value = token.substring(eq + 1).strip();
        
        // Unquote
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).strip();
        }
        
        if (value.isEmpty()) {
            throw new BadMediaTypeSyntaxException(text, "Empty parameter value.");
        }
        
        if (type.equals("text") && name.equals("charset")) {
            value = value.toLowerCase(Locale.ROOT);
        }
        
        return new String[]{ name, value };
    }
    
    private static String stripAndLowerCase(String str) {
        return str.strip().toLowerCase(Locale.ROOT);
    }
    
    
    private final String text, type, subtype;
    private final Map<String, String> params;
    
    
    MediaType(String text, String type, String subtype, Map<String, String> params) {
        this.text    = text;
        this.type    = type;
        this.subtype = subtype;
        this.params  = unmodifiableMap(params);
    }
    
    
    /**
     * Returns the media type.<p>
     * 
     * For example, "text/plain; charset=utf-8" returns "text".
     * 
     * @return the media type (not {@code null})
     */
    public final String type() {
        return type;
    }
    
    /**
     * Returns the media subtype.<p>
     * 
     * For example, "text/plain; charset=utf-8" returns "plain".
     * 
     * @return the media subtype (not {@code null})
     */
    public final String subtype() {
        return subtype;
    }
    
    /**
     * Returns all media parameters.<p>
     * 
     * The returned map is unmodifiable.<p>
     * 
     * @return all media parameters (not {@code null})
     * 
     * @see #parse(CharSequence) 
     */
    public final Map<String, String> parameters() {
        return params;
    }
    
    /**
     * Returns a compatibility score of <i>this</i> media type compared with the
     * specified <i>other</i>.<p>
     * 
     * Primary use-case is for {@link Route} implementations which during the
     * handler selection process ask the question: given a handler's consuming
     * or producing media type, how compatible is it with that of an inbound
     * "Content-Type" or "Accept" media type in the request?<p>
     * 
     * "text/plain" compared with "text/plain" = PERFECT.<br>
     * "text/plain" compared with "text/html" = NOPE.<br>
     * "text/plain" compared with "text/plain; charset=utf-8" = WORKS.<br>
     * "text/plain; charset=utf-8" compared with "text/plain" = NOPE.<br>
     * "text/plain; charset=utf-8" compared with "text/plain; charset=utf-8" = PERFECT.<br>
     * "text/plain" compared with "text/*" = WORKS.<br>
     * "text/*" compared with "text/plain" = WORKS.<br>
     * "text/plain" compared with "text/plain; q=0" = NOPE.<br>
     * 
     * @implNote
     * The implementation assumes all composite String-values have been either
     * lower cased or upper cased and will therefore not consider casing when
     * performing String comparisons.
     * 
     * @param other whom to compare with
     * 
     * @return a compatibility score of <i>this</i> media type compared with the
     *         specified {@code other}
     * 
     * @see Handler
     */
    public final Score compatibility(MediaType other) {
        BiFunction<String, String, Score> typeScore = (a, b) ->
                a.equals(WILDCARD) ? WORKS   :
                b.equals(WILDCARD) ? WORKS   :
                a.equals(b)        ? PERFECT :
                                     NOPE    ;
        
        Score t1 = typeScore.apply(this.type, other.type);
        if (t1 == NOPE) {
            return NOPE;
        }
        
        Score t2 = typeScore.apply(this.subtype, other.subtype);
        if (t2 == NOPE) {
            return NOPE;
        }
        
        double q = 1.;
        if (other instanceof MediaRange) {
            q = ((MediaRange) other).quality();
            
            if (q <= 0.) {
                return NOPE;
            }
        }
        
        if (this.params.isEmpty()) {
            // Works no mather the other's params, but perhaps perfectly?
            return q >= 1. && other.params.isEmpty() && t1 == PERFECT && t2 == PERFECT
                   ? PERFECT : WORKS;
        }
        
        if (!this.params.equals(other.params)) {
            return NOPE;
        }
        
        return q >= 1. ? PERFECT : WORKS;
    }
    
    public enum Score {
        /** Non-compatible or Q-param = 0. */
        NOPE,
        
        /** Fine, within range and/or parameters wasn't evaluated. */
        WORKS,
        
        /** Everything is just perfect (no wildcard, params match, Q = 1). */
        PERFECT;
    }
    
    /**
     * Returns an integer value for specificity.<p>
     * 
     * The returned value is greatest for the least specific media type and
     * lowest for the most specific media type.<p>
     * 
     * The specificity is defined as such:<p>
     * 
     * 7 = if this is NOTHING_AND_ALL<br>
     * 6 = if this is NOTHING<br>
     * 5 = type and subtype is wildcard, no parameters.<br>
     * 4 = type and subtype is wildcard, has parameters.<br>
     * 3 = subtype is wildcard, no parameters.<br>
     * 2 = subtype is wildcard, has parameters.<br>
     * 1 = no wildcard, no parameters.<br>
     * 0 = no wildcard, has parameters.
     * 
     * @return an integer value for specificity
     */
    public final int specificity() {
        if (this == NOTHING_AND_ALL) {
            return 7;
        }
        
        if (this == NOTHING) {
            return 6;
        }
        
        final boolean wildType = type.equals(WILDCARD),
                      wildSub  = subtype.equals(WILDCARD),
                      noParams = params.isEmpty();
        
        return wildType ?  noParams ? 5 : 4 :
               wildSub  ?  noParams ? 3 : 2 :
            /* specific */ noParams ? 1 : 0 ;
    }
    
    private int hash;
    private boolean hashIsZero;
    
    @Override
    public int hashCode() {
        int h = hash;
        
        if (h == 0 && !hashIsZero) {
            h = Objects.hash(type, subtype, params);
            if (h == 0) {
                hashIsZero = true;
            } else {
                hash = h;
            }
        }
        
        return h;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        
        if (!(obj instanceof MediaType)) {
            return false;
        }
        
        // These sentinel objects use identity based equality.
        if (this == NOTHING || obj == NOTHING || this == NOTHING_AND_ALL || obj == NOTHING_AND_ALL) {
            return false;
        }
        
        MediaType that = (MediaType) obj;
        
        return this.type.equals(that.type) &&
               this.subtype.equals(that.subtype) &&
               this.params.equals(that.params);
    }
    
    public final String toString() {
        return text;
    }
    
    public String toStringNormalized() {
        String s = type + "/" + subtype;
        
        if (!params.isEmpty()) {
            s += "; " + params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(joining("; "));
        }
        
        return s;
    }
}
