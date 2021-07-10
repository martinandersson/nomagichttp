package alpha.nomagichttp.testutil;

/**
 * Util class for checking the environment.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Environment {
    private Environment() {
        // Empty
    }
    
    /**
     * Returns {@code true} if it is safe to assume that the executing
     * environment is GitHub Actions, otherwise {@code false}.
     * 
     * @return see JavaDoc
     */
    public static boolean isGitHubActions() {
        return "true".equals(System.getenv("GITHUB_ACTIONS"));
    }
    
    /**
     * Returns {@code true} if it is safe to assume that the executing
     * environment is JitPack, otherwise {@code false}.
     *
     * @return see JavaDoc
     */
    public static boolean isJitPack() {
        return "true".equals(System.getenv("JITPACK"));
    }
    
    /**
     * Returns {@code true} if it is safe to assume that the executing
     * operating system is Windows, otherwise {@code false}.
     * 
     * @return see JavaDoc
     */
    public static boolean isWindows() {
        var os = System.getProperty("os.name");
        return os != null && os.startsWith("Windows");
    }
}