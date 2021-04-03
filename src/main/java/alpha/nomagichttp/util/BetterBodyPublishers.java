package alpha.nomagichttp.util;

import alpha.nomagichttp.message.Response;

import java.net.http.HttpRequest.BodyPublishers;
import java.util.concurrent.Flow;

/**
 * Mirrors the API of {@link BodyPublishers} with semantics specified by
 * {@link Publishers}.<p>
 * 
 * When this class offers an alternative, then it is safe to assume that the
 * alternative is a better choice, for at least one or all of the following
 * reasons: the alternative 1) could be more performant, 2) is thread-safe and
 * non-blocking, 3) has a documented contract and lastly 4) is more compliant
 * with the
 * <a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
 * Reactive Streams</a> specification.<p>
 * 
 * When this class does not offer an alternative, then it is safe to assume that
 * the standard {@code BodyPublishers} factory is adequate.<p>
 * 
 * Currently, however, no alternative is implemented. The intended alternatives
 * will be analogous to {@code BodyPublishers.ofByteArray} (thread-safety
 * issues), consequently also {@code BodyPublishers.ofString} for the same
 * reason and {@code BodyPublishers.ofFile} (blocks). {@code
 * BodyPublishers.noBody} has an alternative already ({@code
 * Publishers.empty()}). {@code BodyPublishers.ofByteArrays} has some flaws but
 * not enough to warrant me investing time bothering with an alternative. {@code
 * BodyPublishers.ofInputStream} is by definition blocking and there's no way
 * around that.
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