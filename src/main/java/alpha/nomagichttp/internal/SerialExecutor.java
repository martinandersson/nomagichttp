package alpha.nomagichttp.internal;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Executes an action by the same thread calling, or if the executor is already
 * busy running a previously added action, schedules the action to be executed
 * in the future, either by the active thread currently operating the executor
 * or another inbound thread - whichever thread wins a race to start at that
 * time.<p>
 * 
 * Scheduled actions are executed serially and in the same order they were
 * added. Recursive calls to {@code execute()} from the same thread running the
 * executor are safe and will simply enqueue the action and then immediately
 * return - just like any other thread would.<p>
 * 
 * Actions are executed with full with memory synchronization in-between. I.e.,
 * the actions may safely read and write common non-volatile fields. Further,
 * anything done by a thread prior to enqueuing an action happens-before the
 * execution of that action.<p>
 * 
 * The first action's earliest synchronization-with point is the constructor of
 * this class; so, any state accessed by the first action must be safely
 * published. For example, initialize all state fields first, then create the
 * {@code SerialExecutor} instance afterwards.<p>
 * 
 * Note that if the actions submitted are long-running and a lot of them queue
 * up, then this risks making a single thread work indefinitely.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class SerialExecutor implements Executor
{
    private final Queue<Runnable> actions;
    private final SeriallyRunnable serial;
    
    SerialExecutor() {
        actions = new ConcurrentLinkedQueue<>();
        serial  = new SeriallyRunnable(this::pollAndExecute);
    }
    
    @Override
    public void execute(Runnable action) {
        actions.add(action);
        serial.run();
    }
    
    private void pollAndExecute() {
        Runnable a;
        while ((a = actions.poll()) != null) {
            a.run();
        }
    }
}