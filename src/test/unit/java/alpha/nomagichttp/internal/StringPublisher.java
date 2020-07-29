package alpha.nomagichttp.internal;

import java.util.ArrayDeque;
import java.util.Queue;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toCollection;

final class StringPublisher extends AbstractUnicastPublisher<String>
{
    private final Queue<String> items;
    
    StringPublisher(String... items) {
        this.items = stream(items).collect(toCollection(ArrayDeque::new));
    }
    
    @Override
    protected String poll() {
        return items.poll();
    }
}