package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.RequestHandler;

import java.text.NumberFormat;
import java.util.Map;

/**
 * Is a media type that allows a "range" of types using a wildcard
 * character ("*") and a "quality" value.<p>
 * 
 * A media range is most commonly used as value for an inbound "Accept" header
 * to drive proactive content negotiation. A media range makes no sense to be
 * used in a "Content-Type" header since the sender ought to know perfectly well
 * what it is that he is sending to the recipient.<p>
 * 
 * Both {@link MediaType} and {@code MediaRange} can be used to specify what a
 * {@link RequestHandler} consumes and/or produces, in order to participate in
 * handler selection with the small caveat that the handler can not specify a
 * quality value other than 1.
 * 
 * 
 * <h2>Type and subtype wildcard</h2>
 * 
 * When used in the "Accept" header to drive content negotiation, both the type
 * and subtype can be a wildcard: "*&#47;*". This would indicate the client is
 * willing to accept any such type as a response. The client can specify the
 * type but leave the subtype as a wildcard indicating he is willing to accept
 * any one of them, for example "text&#47;*".<p>
 * 
 * The client can never specify a wildcard followed by a specific subtype:
 * "*&#47;wrong!".
 * 
 * 
 * <h2>Quality value</h2>
 * 
 * For requests specifying an "Accept" header; the media type parameters may be
 * followed by a special parameter "q" also known as the "quality value", also
 * known as "weight", also known as "declared order of preference", also known
 * as an undefined "lowest acceptable markdown of quality", also known as "put
 * whatever else in here".<p>
 * 
 * Without media parameters declared, the media type might look something like
 * this: "text/plain; q=0.5".<p>
 * 
 * With a media parameter declared it might look something like this:
 * "text/html; charset=utf-8; q=0.4".<p>
 * 
 * What is known about this thing is that it is a floating point value between 0
 * and 1 with at most 3 digits after the decimal point. Further, three numerical
 * values are specified in
 * <a href="https://tools.ietf.org/html/rfc7231#section-5.3.1">RFC 7231</a>.
 * "1" - which is the default - "is the most preferred". "0.001 is the least
 * preferred" and "0" means "not acceptable".<p>
 * 
 * When interpreting this value it will strictly be used for ranking the
 * client's acceptable media types and the value "0" will disqualify any handler
 * consuming and/or producing this media type as a candidate to handle the
 * request.
 * 
 * 
 * <h2>Accept extension parameters</h2>
 * 
 * For requests specifying an "Accept" header, the quality value might actually
 * be followed by yet another set of parameters known as "extension
 * parameters".<p>
 * 
 * It seems like no one on Internet actually knows what they are and after
 * researching piles of source code, many other framework implementations either
 * ignore these or even misinterpret them in such a way that if they were to
 * ever be provided; the application would at best crash, at worse do some weird
 * things.<p>
 * 
 * Until we know what they are, this class will not model the extension
 * parameters.
 * 
 * 
 * <h2>Thread-safety, life-cycle and identity.</h2>
 * 
 * This class is immutable.<p>
 * 
 * In regards to identity, all rules of {@link MediaType} apply. This class
 * does not add quality as a factor. I.e., "text/*; q=0.5" equals "text/*; q=1".
 * 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class MediaRange extends MediaType {
    private final double quality;
    
    MediaRange(String text, String type, String subtype, Map<String, String> parameters, double quality) {
        super(text, type, subtype, parameters);
        this.quality = quality;
    }
    
    /**
     * Returns the quality value.<p>
     * 
     * The returned value is any value between 0 and 1 (inclusive), default 1.
     * 
     * @return the quality value
     * 
     * @see MediaRange
     */
    public double quality() {
        return quality;
    }
    
    /**
     * Overridden to append the "q"-value.
     * 
     * @return a normalized string
     * 
     * @see MediaType#toStringNormalized() 
     */
    public String toStringNormalized() {
        return super.toStringNormalized() + "; q=" + nf().format(quality);
    }
    
    private static NumberFormat nf() {
        return ThreadLocalCache.get(NumberFormat.class, () -> {
            NumberFormat nf = NumberFormat.getInstance();
            nf.setMaximumFractionDigits(3);
            return nf;
        });
    }
}
