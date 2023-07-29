package alpha.nomagichttp.testutil.functional;

/**
 * Constants for functional tests.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Constants {
    private Constants() {
        // Empty
    }
    
    /**
     * Use {@value} to name/identity a test executed by the test client.
     */
    public static final String TEST_CLIENT = "TestClient";
    
    /**
     * Use {@value} to name/identity the client of a compatibility test.
     */
    public static final String OTHER = "{0}";
}