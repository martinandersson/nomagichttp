package alpha.nomagichttp.message;

/**
 * The object that implements this interface may be used to associate arbitrary
 * data with.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface AttributeHolder {
    /**
     * Returns an attributes API bound to this object.<p>
     * 
     * @return an attributes API object bound to this object
     */
    Attributes attributes();
}