package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.route.Route;

import static java.text.MessageFormat.format;

/**
 * Thrown by {@link MediaType#parse(CharSequence)} if a {@code String} can not
 * be parsed into a {@code MediaType}.<p>
 * 
 * The server parse a media type from the request after having matched it
 * against a route. This is needed to lookup which qualified handler to call for
 * the response.<p>
 * 
 * Application-provided request handlers can also provoke this exception when
 * building handlers and responses.<p>
 * 
 * Depending on the origin, a route-level exception handler may observe the
 * presence of different non-null argument values. If the error originates from
 * server code, the exception handler will only have access to the {@link Route}
 * and the {@link Request}, but not the {@link Handler}. If the error originates
 * from the application code post-handler invocation, then also the
 * request-handler argument will be non-null.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see MediaType#parse(CharSequence) 
 */
public final class BadMediaTypeSyntaxException extends RuntimeException
{
    private static final String TEMPLATE = "Can not parse \"{0}\". {1}";
    
    BadMediaTypeSyntaxException(CharSequence parseText, String appendingMessage) {
        this(parseText, appendingMessage, null);
    }
    
    BadMediaTypeSyntaxException(CharSequence parseText, String appendingMessage, Throwable cause) {
        super(format(TEMPLATE, parseText.toString(), appendingMessage), cause);
    }
}