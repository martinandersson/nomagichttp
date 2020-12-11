package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small tests of {@link SerialTransferService}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class SerialTransferServiceTest
{
    @Test
    void one_delivery() {
        List<String> items = new ArrayList<>();
        new SerialTransferService<>(() -> "Hello", items::add).increaseDemand(1);
        assertThat(items).containsExactly("Hello");
    }
    
    @Test
    void two_deliveries() {
        List<String> items = new ArrayList<>();
        new SerialTransferService<>(() -> "Hello", items::add).increaseDemand(2);
        assertThat(items).containsExactly("Hello", "Hello");
    }
    
    @Test
    void synchronous_finish_is_immediate() {
        List<String> items = new ArrayList<>();
        new SerialTransferService<String>(s -> {
            assertTrue(s.finish());
            return "Hello";
        }, items::add).increaseDemand(2);
        
        assertThat(items).containsExactly("Hello");
    }
}