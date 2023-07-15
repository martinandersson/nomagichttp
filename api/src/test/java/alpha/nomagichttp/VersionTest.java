package alpha.nomagichttp;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_0;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

/***
 * Small tests for {@code Version}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class VersionTest
{
    @Test
    void lessThan() {
        assertThat(HTTP_1_0.isLessThan(HTTP_1_1)).isTrue();
    }
    
    @Test
    void greaterThan() {
        assertThat(HTTP_1_1.isGreaterThan(HTTP_1_0)).isTrue();
    }
}