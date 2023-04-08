package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.DefaultContentHeaders;
import alpha.nomagichttp.testutil.Headers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link RequestBody}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestBodyTest
{
    @Test
    void contentLengthAndTransferEncoding_BadRequestException() {
        var crash = headers(
                "Content-Length", "123",
                "Transfer-Encoding", "chunked");
        assertThatThrownBy(() -> RequestBody.of(crash, null))
                .isExactlyInstanceOf(BadRequestException.class)
                .hasMessage("Content-Length and Transfer-Encoding are both present.")
                .hasNoSuppressedExceptions()
                .hasNoCause();
    }
    
    private static DefaultContentHeaders headers(String... pair) {
        return new DefaultContentHeaders(Headers.of(pair), false);
    }
}