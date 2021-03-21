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
 * IOExceptions from broken read operations, I noticed, may have these messages:
 * <pre>
 *     Windows:
 *       java.io.IOException: The specified network name is no longer available
 *     Linux:
 *       java.io.IOException: Connection reset by peer
 * </pre>
 * 
 * On write:
 * <pre>
 *   Windows:
 *     java.io.IOException: An existing connection was forcibly closed by the remote host
 *   Linux:
 *     java.io.IOException: Broken pipe
 * </pre>
 * 
 * Also observed <a href="https://github.com/http4s/blaze/blob/main/core/src/main/scala/org/http4s/blaze/channel/ChannelHead.scala#L43">here</a>:
 * <pre>
 *   "Connection timed out", // Found on Linux NIO1
 *   "Connection reset", // Found on Linux, Java 13
 * </pre>
 * 
 * Not sure from what operation (read/write) these messages originated. The
 * first may probably be ignored since we don't use NIO1?
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@Disabled
class ClientLifeCycleTest
{
    // TODO: Implement
}