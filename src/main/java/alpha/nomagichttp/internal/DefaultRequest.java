package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Request;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BiFunction;

import static java.util.Optional.ofNullable;

final class DefaultRequest implements Request
{
    private final Head head;
    private final Map<String, String> pathParameters;
    private final Flow.Publisher<ByteBuffer> channel;
    
    DefaultRequest(Head head, Map<String, String> pathParameters, Flow.Publisher<ByteBuffer> channel) {
        this.head = head;
        this.pathParameters = pathParameters;
        this.channel = channel;
    }
    
    @Override
    public String method() {
        return head.method();
    }
    
    @Override
    public String target() {
        return head.requestTarget();
    }
    
    @Override
    public String version() {
        return head.httpVersion();
    }
    
    @Override
    public Optional<String> paramFromPath(String name) {
        return ofNullable(pathParameters.get(name));
    }
    
    @Override
    public Optional<String> paramFromQuery(String name) {
        throw new AbstractMethodError("Implement me.");
    }
    
    @Override
    public HttpHeaders headers() {
        return head.headers();
    }
    
    private Body body;
    
    @Override
    public Body body() {
        Body b = body;
        return b != null ? b : (body = new DefaultBody());
    }
    
    private final class DefaultBody implements Request.Body
    {
        @Override
        public CompletionStage<String> toText() {
            // TODO: Charset should be taken out from request headers
            
            BiFunction<byte[], Integer, String> f = (buf, count) ->
                    new String(buf, 0, count, StandardCharsets.US_ASCII);
            
            return convert(f);
        }
        
        @Override
        public <R> CompletionStage<R> convert(BiFunction<byte[], Integer, R> f) {
            HeapSubscriber<R> subscriber = new HeapSubscriber<>(f);
            asPublisher().subscribe(subscriber);
            return subscriber.asCompletionStage();
        }
        
        @Override
        public Flow.Publisher<ByteBuffer> asPublisher() {
            return channel;
        }
    }
}