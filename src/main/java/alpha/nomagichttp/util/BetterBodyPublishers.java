package alpha.nomagichttp.util;

import alpha.nomagichttp.message.Response;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.Path;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * Mirrors the API of {@link BodyPublishers} using semantics specified by
 * {@link Publishers}.<p>
 * 
 * When this class offers an alternative, then it is safe to assume the
 * alternative is a better option to use, for at least one or all of the
 * following reasons: the alternative 1) is more performant, 2) is thread-safe
 * and non-blocking<sup>1</sup>, 3) has a documented contract and lastly 4) is
 * more compliant with the
 * <a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
 * Reactive Streams</a> specification.<p>
 * 
 * <sup>1</sup> Some JDK-provided types block (!), such as {@link
 * HttpRequest.BodyPublishers#ofFile(Path)} and {@link
 * HttpRequest.BodyPublishers#ofInputStream(Supplier)}
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Response.Builder#body(Flow.Publisher) 
 */
public final class BetterBodyPublishers
{
    private BetterBodyPublishers() {
        // Empty
    }
    
    // TODO: Implement
}