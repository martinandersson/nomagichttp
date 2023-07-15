package alpha.nomagichttp.testutil.functional;

import alpha.nomagichttp.util.Streams;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;

final class ByteBufferPublisher implements Flow.Publisher<ByteBuffer> {
    private final List<ByteBuffer> data;
    
    ByteBufferPublisher(byte[] first, byte[]... more) {
        data = Streams.stream(first, more)
                      .map(ByteBuffer::wrap)
                      .toList();
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> s) {
        var it = data.iterator();
        s.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                if (n <= 0) {
                    s.onError(new IllegalArgumentException());
                    return;
                }
                while (n-- > 0 && it.hasNext()) {
                    s.onNext(it.next());
                }
                if (!it.hasNext()) {
                    s.onComplete();
                }
            }
            @Override
            public void cancel() {
                // Empty
            }
        });
    }
}
