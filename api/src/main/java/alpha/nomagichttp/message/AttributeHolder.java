package alpha.nomagichttp.message;

/**
 * Adds {@link #attributes()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface AttributeHolder {
    /**
     * Returns an attributes API bound to this object.
     * 
     * @return an attributes API object bound to this object
     */
    Attributes attributes();
}