package alpha.nomagichttp.message;

/**
 * Adds {@link #attributes()}.
 */
public interface HasAttributes {
    /**
     * Returns attributes.
     * 
     * @return attributes (never {@code null})
     */
    Attributes attributes();
}
