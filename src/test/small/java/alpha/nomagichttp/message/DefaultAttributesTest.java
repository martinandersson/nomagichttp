package alpha.nomagichttp.message;

import alpha.nomagichttp.internal.DefaultAttributes;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link DefaultAttributesTest}
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultAttributesTest {
    private final Attributes testee = new DefaultAttributes();
    
    @Test
    void attributes_set_get() {
        Object o = testee.set("msg", "hello");
        assertThat(o).isNull();
        String n = testee.getAny("msg");
        assertThat(n).isEqualTo("hello");
    }
    
    @Test
    void class_cast_exception_immediate() {
        testee.set("int", 123);
        assertThatThrownBy(() -> {
            String crash = testee.getAny("int");
        }).isExactlyInstanceOf(ClassCastException.class);
    }
    
    @Test
    void class_cast_exception_delayed() {
        testee.set("int", 123);
        Optional<String> opt = testee.getOptAny("int");
        assertThatThrownBy(() -> {
            String crash = opt.get();
        }).isExactlyInstanceOf(ClassCastException.class);
    }
}