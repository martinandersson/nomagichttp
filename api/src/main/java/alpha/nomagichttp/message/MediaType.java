package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.Strings;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static alpha.nomagichttp.message.MediaType.Score.NOPE;
import static alpha.nomagichttp.message.MediaType.Score.PERFECT;
import static alpha.nomagichttp.message.MediaType.Score.WORKS;
import static java.lang.Double.parseDouble;
import static java.lang.System.Logger;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Collections.unmodifiableMap;
import static java.util.OptionalDouble.empty;
import static java.util.OptionalDouble.of;
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
 * {@link RequestHandler} to learn more on that.<p>
 * 
 * Common media types are available as public static constants in this class.
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
 * In reality, it's probably easier to both understand and implement media type
 * logic if the application can skip the parameters. The previous example could
 * be rewritten as "application/vnd.example.v2+json". This example could be
 * further simplified if the major version is moved to the resource URI.<p>
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
 * @see HttpConstants.HeaderName#CONTENT_TYPE
 * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.1">RFC 7231 §3.1.1</a>
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class MediaType
{
    private static final Logger LOG = System.getLogger(MediaType.class.getPackageName());
    private static final String
            WILDCARD = "*", q = "q", Q = "Q", utf_8 = "utf-8";
    
    /**
     * A sentinel media type that can be used as a handler's consuming media
     * type to indicate that the handler consumes nothing and is not willing to
     * handle requests that has the "Content-Type" header set.<p>
     * 
     * <strong>Not</strong> valid as a handler's producing media type.
     * 
     * @see RequestHandler
     */
    public static final MediaType NOTHING
            = new MediaType("<nothing>", null, null, Map.of()) {};
    
    // All modifiers repeated because otherwise the JavaDoc won't be picked up lol
    
    /**
     * A sentinel media type that can be used as a handler's consuming media
     * type to indicate that the handler is willing to handle requests
     * irrelevant of the "Content-Type" header; it can be missing as well as it
     * can have any value.<p>
     * 
     * <strong>Not</strong> valid as a handler's producing media type.
     * 
     * @see RequestHandler
     */
    public static final MediaType NOTHING_AND_ALL
            = new MediaType("<nothing and all>", null, null, Map.of()) {};
    
    /**
     * A sentinel media type that can be used as a handler's consuming and/or
     * producing media type to indicate that the handler is willing to consume
     * and/or produce any media type.<p>
     * 
     * A handler that declares this media type as producing may still chose to
     * respond a different representation or perhaps a message with no body at
     * all. This is fully up to the handler implementation.
     * 
     * @see RequestHandler
     */
    public static final MediaType ALL = parse0("*/*");
    
    // TODO: Usually there's no point in repeating modifiers.
    //       Alas Java < 17 will not pick up the JavaDoc if they aren't.
    
    /** Text. Value: "text/plain". File extension: ".txt". */
    public static final MediaType TEXT_PLAIN = parse0("text/plain");
    
    /** Text. Value: "text/plain; charset=utf-8". File extension: ".txt". */
    public static final MediaType TEXT_PLAIN_UTF8 = parse0("text/plain; charset=utf-8");
    
    /** HyperText Markup Language. Value: "text/html". File extension: ".html". */
    public static final MediaType TEXT_HTML = parse0("text/html");
    
    /** HyperText Markup Language. Value: "text/html; charset=utf-8". File extension: ".html". */
    public static final MediaType TEXT_HTML_UTF8 = parse0("text/html; charset=utf-8");
    
    /** Cascading Style Sheets. Value: "text/css". File extension: ".css". */
    public static final MediaType TEXT_CSS = parse0("text/css");
    
    /** Cascading Style Sheets. Value: "text/css; charset=utf-8". File extension: ".css". */
    public static final MediaType TEXT_CSS_UTF8 = parse0("text/css; charset=utf-8");
    
    /** Comma-Separated Values. Value: "text/csv". File extension: ".csv". */
    public static final MediaType TEXT_CSV = parse0("text/csv");
    
    /** Comma-Separated Values. Value: "text/csv; charset=utf-8". File extension: ".csv". */
    public static final MediaType TEXT_CSV_UTF8 = parse0("text/csv; charset=utf-8");
    
    /** JavaScript. Value: "text/javascript". File extension: ".js". */
    public static final MediaType TEXT_JAVASCRIPT = parse0("text/javascript");
    
    /** JavaScript. Value: "text/javascript; charset=utf-8". File extension: ".js". */
    public static final MediaType TEXT_JAVASCRIPT_UTF8 = parse0("text/javascript; charset=utf-8");
    
    /** Any kind of binary data. Value: "application/octet-stream". */
    public static final MediaType APPLICATION_OCTET_STREAM = parse0("application/octet-stream");
    
    /** JSON format. Value: "application/json". File extension: ".json". */
    public static final MediaType APPLICATION_JSON = parse0("application/json");
    
    /** JSON format. Value: "application/json; charset=utf-8". File extension: ".json". */
    public static final MediaType APPLICATION_JSON_UTF8 = parse0("application/json; charset=utf-8");
    
    /** ZIP archive. Value: "application/zip". File extension: ".zip". */
    public static final MediaType APPLICATION_ZIP = parse0("application/zip");
    
    /** GZip compressed archive. Value: "application/gzip". File extension: ".gz". */
    public static final MediaType APPLICATION_GZIP = parse0("application/gzip");
    
    /** RAR archive. Value: "application/vnd.rar". File extension: ".rar". */
    public static final MediaType APPLICATION_VND_RAR = parse0("application/vnd.rar");
    
    /** TAR archive. Value: "application/x-tar". File extension: ".tar". */
    public static final MediaType APPLICATION_X_TAR = parse0("application/x-tar");
    
    /** 7-zip archive. Value: "application/x-7z-compressed". File extension: ".7z". */
    public static final MediaType APPLICATION_X_7Z_COMPRESSED = parse0("application/x-7z-compressed");
    
    /** Adobe Portable Document Format. Value: "application/pdf". File extension: ".pdf". */
    public static final MediaType APPLICATION_PDF = parse0("application/pdf");
    
    /** Java archive. Value: "application/java-archive". File extension: ".jar". */
    public static final MediaType APPLICATION_JAVA_ARCHIVE = parse0("application/java-archive");
    
    /** Portable Network Graphics. Value: "image/png". File extension: ".png". */
    public static final MediaType IMAGE_PNG = parse0("image/png");
    
    /** Graphics Interchange Format. Value: "image/gif". File extension: ".gif". */
    public static final MediaType IMAGE_GIF = parse0("image/gif");
    
    /** JPEG image. Value: "image/jpeg". File extension: ".jpg". */
    public static final MediaType IMAGE_JPEG = parse0("image/jpeg");
    
    /** Windows OS/2 bitmap graphics. Value: "image/bmp". File extension: ".bmp". */
    public static final MediaType IMAGE_BMP = parse0("image/bmp");
    
    /** Scalable vector graphics. Value: "image/svg+xml". File extension: ".svg". */
    public static final MediaType IMAGE_SVG_XML = parse0("image/svg+xml");
    
    /** Tagged Image File Format. Value: "image/tiff". File extension: ".tif/.tiff". */
    public static final MediaType IMAGE_TIFF = parse0("image/tiff");
    
    /** Waveform Audio Format. Value: "audio/wav". File extension: ".wav". */
    public static final MediaType AUDIO_WAV = parse0("audio/wav");
    
    /** AAC audio. Value: "audio/aac". File extension: ".aac". */
    public static final MediaType AUDIO_AAC = parse0("audio/aac");
    
    /** Musical Instrument Digital Interface. Value: "audio/midi". File extension: ".mid/.midi". */
    public static final MediaType AUDIO_MIDI = parse0("audio/midi");
    
    /** MP3 audio. Value: "audio/mpeg". File extension: ".mp3". */
    public static final MediaType AUDIO_MPEG = parse0("audio/mpeg");
    
    /** OGG audio. Value: "audio/ogg". File extension: ".oga". */
    public static final MediaType AUDIO_OGG = parse0("audio/ogg");
    
    /** OGG video. Value: "video/ogg". File extension: ".ogv". */
    public static final MediaType VIDEO_OGG = parse0("video/ogg");
    
    /** MP4 video. Value: "video/mp4". File extension: ".mp4". */
    public static final MediaType VIDEO_MP4 = parse0("video/mp4");
    
    /** MPEG video. Value: "video/mpeg". File extension: ".mpeg". */
    public static final MediaType VIDEO_MPEG = parse0("video/mpeg");
    
    private static final Map<String, MediaType> CACHE = buildCache();
    
    private static Map<String, MediaType> buildCache() {
        // All constants + "text/*" we put in cache
        Map<String, MediaType> m = new HashMap<>();
        Stream.of(
            parse0("text/*"), parse0("text/*; charset=utf-8"),
            ALL,
            TEXT_PLAIN,       TEXT_PLAIN_UTF8,
            TEXT_HTML,        TEXT_HTML_UTF8,
            TEXT_CSS,         TEXT_CSS_UTF8,
            TEXT_CSV,         TEXT_CSV_UTF8,
            TEXT_JAVASCRIPT,  TEXT_JAVASCRIPT_UTF8,
            APPLICATION_OCTET_STREAM,
            APPLICATION_JSON, APPLICATION_JSON_UTF8,
            APPLICATION_ZIP,
            APPLICATION_GZIP,
            APPLICATION_VND_RAR,
            APPLICATION_X_TAR,
            APPLICATION_X_7Z_COMPRESSED,
            APPLICATION_PDF,
            APPLICATION_JAVA_ARCHIVE,
            IMAGE_PNG, IMAGE_GIF, IMAGE_JPEG, IMAGE_BMP, IMAGE_SVG_XML, IMAGE_TIFF,
            AUDIO_WAV, AUDIO_AAC, AUDIO_MIDI, AUDIO_MPEG, AUDIO_OGG,
            VIDEO_OGG, VIDEO_MP4, VIDEO_MPEG).forEach(mt -> {
            
            final String org = mt.toString();
            putInCache(mt, m);
            
            // + one copy without the space after ";"
            // e.g. "blabla; charset=utf-8"? Also add ";charset=utf-8"
            int sp = org.indexOf(' ');
            MediaType noSpace = null;
            if (sp != -1) {
                var str2 = org.substring(0, sp) + org.substring(sp + 1);
                noSpace = putInCache(str2, m);
            }
            
            // + copy with a quoted "utf-8"
            // e.g. "blabla; charset=utf-8"? Also add "; charset=\"utf-8\""
            assert !org.contains("\"");
            if (org.endsWith(utf_8)) {
                putInCacheWithQuotedUtf8(mt, m);
            }
            if (noSpace != null && noSpace.toString().endsWith(utf_8)) {
                putInCacheWithQuotedUtf8(noSpace, m);
            }
        });
        return m;
    }
    
    private static MediaType putInCache(String mt, Map<String, MediaType> map) {
        var p = parse0(mt);
        putInCache(p, map);
        return p;
    }
    
    private static void putInCache(MediaType mt, Map<String, MediaType> map) {
        var old = map.put(mt.toString(), mt);
        assert old == null;
    }
    
    private static void putInCacheWithQuotedUtf8(
            MediaType mt, Map<String, MediaType> map)
    {
        var quoted = mt.toString().substring(0,
                // Cut after "charset="
                mt.toString().length() - utf_8.length()) +
                // Add quoted:
                "\"" + utf_8 + "\"";
        putInCache(quoted, map);
    }
    
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
     * type parameters and the beginning of media range {@link MediaRange
     * extension parameters}, which are logged but otherwise ignored.<p>
     * 
     * There is no defined syntax for parameter values. Almost all parameter
     * values will be extracted at face value, except for quoted strings which
     * will be unquoted ("blabla" -&gt; blabla).<p>
     * 
     * The only parameter value that will be lowered is the "charset" parameter
     * for all "text/*" media types. I.e., treated case-insensitively when
     * routing a request to a handler (
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
     * The returned instance may be new, or it may have been retrieved from a
     * cache.
     * 
     * @param text to parse
     * 
     * @throws NullPointerException
     *             if {@code text} is {@code null}
     * 
     * @throws MediaTypeParseException
     *             if there's not exactly one forward slash in "type/subtype", or
     *             type or subtype is empty, or
     *             there's a wildcard type but not a wildcard subtype, or
     *             a parameter name- or value is empty, or
     *             a parameter has been specified more than once, or
     *             the q-parameter can not be parsed to a double
     * 
     * @return a parsed media type (never {@code null})
     */
    public static MediaType parse(final String text) {
        var cached = CACHE.get(text);
        return cached != null ? cached : parse0(text);
    }
    
    static MediaType parse0(final String txt) {
        // First part is "type/subtype", possibly followed by ";params"
        final String[] tokens = Strings.split(txt, ';', '"').toArray(String[]::new);
        final String[] types = parseTypes(tokens[0], txt);
        final String type = types[0],
                  subtype = types[1];
        
        Map<String, String> params = parseParams(type, tokens, 1, true, txt);
        final int size = params.size();
        
        String qStr = params.remove(q); // Most likely lower case..
        if (qStr == null) {
            qStr = params.remove(Q); // ..but can be upper case.
        }
        OptionalDouble qVal = empty();
        if (qStr != null) {
            try {
                qVal = of(parseDouble(qStr));
            }
            catch (NumberFormatException e) {
                throw new MediaTypeParseException(txt,
                        "Non-parsable value for " + q + "-parameter.", e);
            }
        }
        
        Map<String, String> extension = parseParams(type, tokens, 1 + size, false, txt);
        if (!extension.isEmpty()) {
            LOG.log(WARNING, () -> "Media type extension parameters ignored: " + extension);
        }
        
        return type.equals(WILDCARD) || subtype.equals(WILDCARD) || qVal.isPresent() ?
                new MediaRange(txt, type, subtype, params, qVal.orElse(1)) :
                new MediaType(txt, type, subtype, params);
    }
    
    private static String[] parseTypes(String tkn, String txt) {
        final String[] raw = tkn.split("/");
        unacceptable(raw.length != 2, txt,
            "Expected exactly one forward slash in <type/subtype>.");
        
        final String type = stripAndLowerCase(raw[0]);
        unacceptable(type.isEmpty(), txt, "Type is empty.");
        
        final String subtype = stripAndLowerCase(raw[1]);
        unacceptable(subtype.isEmpty(), txt, "Subtype is empty.");
        
        unacceptable(type.equals(WILDCARD) && !subtype.equals(WILDCARD), txt,
            "Wildcard type but not a wildcard subtype.");
        
        return type == raw[0] && subtype == raw[1] ? raw :
                new String[]{ type, subtype };
    }
    
    private static Map<String, String> parseParams(
            String type, String[] tokens, int offset, boolean stopAfterQ, String txt)
    {
        Map<String, String> params = Collections.emptyMap();
        
        for (int i = offset; i < tokens.length; ++i) {
            String[] nameAndValue = parseParam(type, tokens[i], txt);
            
            if (params.isEmpty()) {
                params = new LinkedHashMap<>();
            }
            var old = params.put(nameAndValue[0], nameAndValue[1]);
            // "It is an error for a specific parameter to be specified more than once."
            // https://datatracker.ietf.org/doc/html/rfc6838#section-4.3
            unacceptable(old != null, txt, "Duplicated parameters.");
            
            if (stopAfterQ && nameAndValue[0].equalsIgnoreCase(q)) {
                break;
            }
        }
        
        return params;
    }
    
    private static String[] parseParam(String type, String tkn, String txt) {
        int eq = tkn.indexOf("=");
        unacceptable(eq == -1, txt, "A parameter has no assigned value.");
        
        String name = stripAndLowerCase(tkn.substring(0, eq));
        unacceptable(name.isEmpty(), txt, "Empty parameter name.");
        
        String value = Strings.unquote(tkn.substring(eq + 1).strip());
        unacceptable(value.isEmpty(), txt, "Empty parameter value.");
        
        if (type.equals("text") && name.equals("charset")) {
            value = value.toLowerCase(Locale.ROOT);
        }
        return new String[]{ name, value };
    }
    
    private static void unacceptable(boolean whatIs, String parseText, String appendMsg) {
        if (whatIs) throw new MediaTypeParseException(parseText, appendMsg);
    }
    
    private static String stripAndLowerCase(String str) {
        return str.strip().toLowerCase(Locale.ROOT);
    }
    
    
    private final String text, type, subtype;
    private final Map<String, String> params;
    
    
    /* package-private for tests */
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
     * The returned map is unmodifiable.
     * 
     * @return all media parameters (not {@code null})
     * 
     * @see #parse(String) 
     */
    public final Map<String, String> parameters() {
        return params;
    }
    
    /**
     * {@return a compatibility score of <i>this</i> media type compared with the
     * specified <i>other</i>}<p>
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
     * @see RequestHandler
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
    
    /**
     * Compatibility score; how much compatible are two media types?
     */
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
        // Copy-paste from String.hashCode()
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
        
        if (!(obj instanceof MediaType other)) {
            return false;
        }
        
        // These sentinel objects use identity based equality.
        // (i.e. for the same instance, the method has already returned true)
        if (this == NOTHING || obj == NOTHING ||
            this == NOTHING_AND_ALL || obj == NOTHING_AND_ALL) {
            return false;
        }
        
        return this.type.equals(other.type) &&
               this.subtype.equals(other.subtype) &&
               this.params.equals(other.params);
    }
    
    /**
     * Returns the same string instance used when this media type was parsed.<p>
     * 
     * Except for sentinel values which were not constructed through parsing.
     * Sentinel values use a placeholder value instead, enclosed in diamond
     * brackets, e.g. &lt;nothing and all&gt;.
     * 
     * @return the same string instance used when this media type was parsed,
     *         or &lt;sentinel&gt;
     */
    public final String toString() {
        return text;
    }
    
    /**
     * Returns a normalized string of the format "type" + "/" + subtype,
     * possibly followed by "; q=" + value.
     * 
     * @return a normalized string
     */
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
