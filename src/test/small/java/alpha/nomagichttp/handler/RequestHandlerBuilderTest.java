package alpha.nomagichttp.handler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests of {@link RequestHandler.Builder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestHandlerBuilderTest
{
    @Test
    void of_method_null() {
        assertThatThrownBy(() -> RequestHandler.builder(null))
                .isExactlyInstanceOf(NullPointerException.class);
    }
    
    @Test
    void of_method_empty() {
        assertThatThrownBy(() -> RequestHandler.builder("Empty method."))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Whitespace in method \"Empty method.\".");
    }
    
    @Test
    void of_method_whitespace() {
        assertThatThrownBy(() -> RequestHandler.builder("< >"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Whitespace in method \"< >\".");
    }
}