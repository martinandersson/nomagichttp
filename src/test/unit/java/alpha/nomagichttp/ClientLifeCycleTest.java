package alpha.nomagichttp;

import org.junit.jupiter.api.Disabled;

/**
 * This is the future home of client life-cycle tests.<p>
 * 
 * There's a couple of them in {@code DetailedEndToEndTest}, specifically the
 * "client_closeChannel" test cases, and more. These will be reworked and moved
 * to this class, but we need a solid response-pipeline first.
 * DetailedEndToEndTest should strictly be concerned only with HTTP protocol
 * semantics.<p>
 * 
 * Then, we want to discriminate between client closing his input/output
 * streams. Server should, for example, not close channel if client closed only
 * his output stream but is still waiting on a response. It's likely that the
 * error handler will receive ChannelOperations in order to probe the state of
 * the client channel.<p>
 * 
 * Disconnects are easy to reproduce, for example, just run an exchange on
 * client and close. Server thought exchange would continue but receives a weird
 * IOException.<p>
 * 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@Disabled
class ClientLifeCycleTest
{
    // TODO: Implement
}