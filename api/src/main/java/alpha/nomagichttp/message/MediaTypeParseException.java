package alpha.nomagichttp.message;

import java.io.Serial;

import static java.text.MessageFormat.format;

/**
 * Thrown by {@link MediaType#parse(String)} if a {@code String} can not be
 * parsed into a {@code MediaType}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see MediaType#parse(String) 
 */
public final class MediaTypeParseException extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    private static final String TEMPLATE = "Can not parse \"{0}\". {1}";
    
    private final String text;
    
    MediaTypeParseException(String parseText, String appendingMessage) {
        this(parseText, appendingMessage, null);
    }
    
    MediaTypeParseException(String parseText, String appendingMessage, Throwable cause) {
        super(format(TEMPLATE, parseText, appendingMessage), cause);
        text = parseText;
    }
    
    /**
     * {@return the text input that attempted to parse}
     */
    public String getText() {
        return text;
    }
}