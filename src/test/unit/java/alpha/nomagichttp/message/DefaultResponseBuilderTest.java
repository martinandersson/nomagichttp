package alpha.nomagichttp.message;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.util.Publishers.empty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Small tests of {@link DefaultResponse.Builder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultResponseBuilderTest
{
    @Test
    void happy_path() {
        Response r = Response.newBuilder()
                .httpVersion("HTTP/1.1")
                .statusCode(200)
                .reasonPhrase("OK")
                .build();
        
        assertThat(r.statusLine()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(r.mustCloseAfterWrite()).isFalse();
        assertSame(r.body(), empty());
    }
    
    @Test
    void no_httpVersion_IllegalArgumentException() {
        Response.Builder b = Response.newBuilder()
                //.httpVersion("HTTP/1.1")
                .statusCode(200)
                .reasonPhrase("OK");
        
        assertThatThrownBy(b::build)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP version not set.");
    }
    
    @Test
    void no_statusCode_IllegalArgumentException() {
        Response.Builder b = Response.newBuilder()
                .httpVersion("HTTP/1.1")
                //.statusCode(200)
                .reasonPhrase("OK");
        
        assertThatThrownBy(b::build)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Status code not set.");
    }
    
    @Test
    void no_reasonPhrase_Unknown() {
        Response r = Response.newBuilder()
                .httpVersion("HTTP/1.1")
                .statusCode(200)
                //.reasonPhrase("OK")
                .build();
        
        assertThat(r.statusLine()).isEqualTo("HTTP/1.1 200 Unknown");
    }
    
    @Test
    void future_changes_does_not_affect_old_builders() {
        Response.Builder b0 = Response.newBuilder().httpVersion("").reasonPhrase(""),
                         b1 = b0.statusCode(1),
                         b2 = b1.statusCode(2);
        
        assertThat(b1.build().statusLine()).isEqualTo(" 1 ");
        assertThat(b2.build().statusLine()).isEqualTo(" 2 ");
    }
}