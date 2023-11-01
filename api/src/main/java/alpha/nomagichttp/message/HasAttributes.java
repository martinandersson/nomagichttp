package alpha.nomagichttp.message;

/**
 * Adds {@link #attributes()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface HasAttributes {
    /**
     * Returns attributes.
     * 
     * @return attributes (never {@code null})
     */
    Attributes attributes();
}
