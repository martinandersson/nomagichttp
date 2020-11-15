package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Publishers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpHeaders;
import java.nio.channels.NetworkChannel;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Map;

import static alpha.nomagichttp.util.Headers.of;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests of {@link DefaultRequest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultRequestTest
{
    @Test
    void body_toText_happyPath() {
        Request req = createRequest(of(
                "Content-Length", "3"),
                "abc");
        
        assertThat(req.body().get().toText()).isCompletedWithValue("abc");
    }
    
    @Test
    void body_toText_BadHeaderException() {
        Request req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "first",
                "Content-Type", "second"),
                "abc");
        
        assertThat(req.body().get().toText()).hasFailedWithThrowableThat()
                .isExactlyInstanceOf(BadHeaderException.class)
                .hasMessage("Multiple Content-Type values in request.");
    }
    
    @Test
    void body_toText_IllegalCharsetNameException() {
        Request req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "text/plain;charset=."),
                "abc");
        
        assertThat(req.body().get().toText()).hasFailedWithThrowableThat()
                .isExactlyInstanceOf(IllegalCharsetNameException.class);
                // Message not specified
    }
    
    @Test
    void body_toText_UnsupportedCharsetException() {
        Request req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "text/plain;charset=from-another-galaxy"),
                "abc");
        
        assertThat(req.body().get().toText()).hasFailedWithThrowableThat()
                .isExactlyInstanceOf(UnsupportedCharsetException.class);
    }
    
    private static DefaultRequest createRequest(HttpHeaders headers, String body) {
        RequestHead rh = new RequestHead(
                "test-method",
                "test-requestTarget",
                "test-httpVersion",
                headers);
        
        return new DefaultRequest(
                rh,
                Map.of(),
                Publishers.singleton(PooledByteBuffers.wrap(body, US_ASCII)),
                Mockito.mock(NetworkChannel.class));
    }
}