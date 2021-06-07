package alpha.nomagichttp.testutil;

import java.io.IOException;

/**
 * A {@code Runnable} that may throw {@code IOException}.
 */
@FunctionalInterface
public interface IORunnable 
{
    /**
     * Run.
     * 
     * @throws IOException if shit happens
     */
    void run() throws IOException;
}