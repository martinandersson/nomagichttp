package alpha.nomagichttp.testutil.functional;

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
     * {@return {@code true} if it is safe to assume that the executing
     * environment is GitHub Actions, otherwise {@code false}}
     */
    public static boolean isGitHubActions() {
        return "true".equals(System.getenv("GITHUB_ACTIONS"));
    }
    
    /**
     * {@return {@code true} if it is safe to assume that the executing
     * environment is JitPack, otherwise {@code false}}
     */
    public static boolean isJitPack() {
        return "true".equals(System.getenv("JITPACK"));
    }
    
    /**
     * {@return {@code true} if it is safe to assume that the executing
     * operating system is Windows, otherwise {@code false}}
     */
    public static boolean isWindows() {
        return isOS("Windows");
    }
    
    /**
     * {@return {@code true} if it is safe to assume that the executing
     * operating system is Linux, otherwise {@code false}}
     */
    public static boolean isLinux() {
        return isOS("Linux");
    }
    
    /**
     * {@return {@code true} if it is safe to assume that the executing
     * JVM is Java 11, otherwise {@code false}}
     */
    public static boolean isJava11() {
        return isJava(11);
    }
    
    /**
     * {@return {@code true} if it is safe to assume that the executing
     * JVM is Java 13, otherwise {@code false}}
     */
    public static boolean isJava13() {
        return isJava(13);
    }
    
    private static boolean isOS(String os) {
        var v = System.getProperty("os.name");
        return v != null && v.startsWith(os);
    }
    
    private static boolean isJava(int major) {
        var v = System.getProperty("java.version");
        return v != null && v.startsWith(major + ".");
    }
}