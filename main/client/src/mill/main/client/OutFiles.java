package mill.main.client;

/**
 * Central place containing all the files that live inside the `out/` folder
 * and documentation about what they do
 */
public class OutFiles {
    /**
     * Path of the Mill `out/` folder
     */
    final public static String out = "out";

    /**
     * Path of the Mill "meta-build", used to compile the `build.sc` file so we can
     * run the primary Mill build. Can be nested for multiple stages of bootstrapping
     */
    final public static String millBuild = "mill-build";

    /**
     * A parallel performance and timing profile generated for every Mill execution.
     * Can be loaded into the Chrome browser chrome://tracing page to visualize where
     * time in a build is being spent
     */
    final public static String millChromeProfile = "mill-chrome-profile.json";

    /**
     * A sequential profile containing rich information about the tasks that were run
     * as part of a build: name, duration, cached, dependencies, etc.. Useful to help
     * understand what tasks are taking time in a build run and why those tasks are
     * being executed
     */
    final public static String millProfile = "mill-profile.json";

    /**
     * Long lived metadata about the Mill bootstrap process that persists between runs:
     * workers, watched files, classpaths, etc.
     */
    final public static String millRunnerState = "mill-runner-state.json";

    /**
     * Subfolder of `out/` that contains the machinery necessary for a single Mill background
     * server: metadata files, pipes, logs, etc.
     */
    final public static String millWorker = "mill-worker-";

    /**
     * Subfolder of `out/` used to contain the Mill subprocess when run in no-server mode
     */
    final public static String millNoServer = "mill-no-server";

}
