package alpha.nomagichttp.largetest;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Effectively superseded by {@code TwoHundredRequestsFromSameClient.big_xxx}.
 * Kept around because this is a slightly easier test case to run and debug in
 * the event of failure.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class BigTextRequest extends AbstractSingleClientTest
{
    @BeforeAll
    static void addHandler() {
        RequestHandler echo = POST().apply(req ->
                req.body().toText().thenApply(Responses::text));
        
        server().add("/echo", echo);
    }
    
    @Test
    void test() throws IOException, InterruptedException {
        // ChannelByteBufferPublisher has 5 of these in a pool, we use 20
        final int size = 20 * 16 * 1_024;
        final String expected = DataUtil.text(size);
        System.out.println("Expected size: " + expected.length());
        System.out.println("First ten: " + expected.substring(0, 10));
        System.out.println("Last ten:  " + expected.substring(expected.length() - 10));
        System.out.println();
        
        HttpResponse<String> res = postAndReceiveText("/echo", expected);
        assertThat(res.statusCode()).isEqualTo(200);
        
        final String actual = res.body();
        System.out.println("Actual size: " + actual.length());
        System.out.println("First ten: " + actual.substring(0, 10));
        System.out.println("Last ten:  " + actual.substring(expected.length() - 10));
        
        assertThat(actual).isEqualTo(expected);
    }
}