package alpha.nomagichttp.message;

import static java.text.MessageFormat.format;

/**
 * Thrown by {@link MediaType#parse(CharSequence)} if a {@code String} can not
 * be parsed into a {@code MediaType}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see MediaType#parse(CharSequence) 
 */
public final class MediaTypeParseException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    private static final String TEMPLATE = "Can not parse \"{0}\". {1}";
    
    MediaTypeParseException(CharSequence parseText, String appendingMessage) {
        this(parseText, appendingMessage, null);
    }
    
    MediaTypeParseException(CharSequence parseText, String appendingMessage, Throwable cause) {
        super(format(TEMPLATE, parseText.toString(), appendingMessage), cause);
    }
}