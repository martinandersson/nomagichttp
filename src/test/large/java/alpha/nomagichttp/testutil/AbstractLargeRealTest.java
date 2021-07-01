package alpha.nomagichttp.testutil;

import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

/**
 * Configured to use an extended server+client scope, no log recording and
 * default log level.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@TestInstance(PER_CLASS)
public abstract class AbstractLargeRealTest extends AbstractRealTest {
    static void beforeAll() {
        // Use default log level
    }
    
    /**
     * Constructs this object.
     */
    protected AbstractLargeRealTest() {
        super(false, false, false);
    }
}