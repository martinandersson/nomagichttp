package alpha.nomagichttp;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.Config.DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests of {@link Config}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ConfigTest {
    @Test
    void parentStateUnaffected() {
        final int was = DEFAULT.maxRequestHeadSize();
        final var mod = DEFAULT.toBuilder().maxRequestHeadSize(123).build();
        
        // Default not modified
        assertThat(DEFAULT.maxRequestHeadSize()).isEqualTo(was);
        // Mod is
        assertThat(mod.maxRequestHeadSize()).isEqualTo(123);
    }
}