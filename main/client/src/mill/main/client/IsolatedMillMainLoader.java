package mill.main.client;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

class IsolatedMillMainLoader {

    public static class LoadResult {

        public final Optional<Method> millMainMethod;
        public final boolean canLoad;
        public final long loadTime;

        public LoadResult(Optional<Method> millMainMethod, final long loadTime) {
            this.millMainMethod = millMainMethod;
            this.canLoad = millMainMethod.isPresent();
            this.loadTime = loadTime;
        }
    }

    private static Optional<LoadResult> canLoad = Optional.empty();

    public static LoadResult load() {
        if (canLoad.isPresent()) {
            return canLoad.get();
        } else {
            long startTime = System.currentTimeMillis();
            Optional<Method> millMainMethod = Optional.empty();
            try {
                Class<?> millMainClass = IsolatedMillMainLoader.class.getClassLoader().loadClass("mill.runner.MillMain");
                Method mainMethod = millMainClass.getMethod("main", String[].class);
                millMainMethod = Optional.of(mainMethod);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                millMainMethod = Optional.empty();
            }

            long loadTime = System.currentTimeMillis() - startTime;
            LoadResult result = new LoadResult(millMainMethod, loadTime);
            canLoad = Optional.of(result);
            return result;
        }
    }

    public static void runMain(String[] args) throws Exception {
        LoadResult loadResult = load();
        if (loadResult.millMainMethod.isPresent()) {
            if (!MillEnv.millJvmOptsAlreadyApplied() && MillEnv.millJvmOptsFile().exists()) {
                System.err.println("Launching Mill as sub-process ...");
                int exitVal = launchMillAsSubProcess(args);
                System.exit(exitVal);
            } else {
                // launch mill in-process
                // it will call System.exit for us
                Method mainMethod = loadResult.millMainMethod.get();
                mainMethod.invoke(null, new Object[]{args});
            }
        } else {
            throw new RuntimeException("Cannot load mill.runner.MillMain class");
        }
    }

    private static int launchMillAsSubProcess(String[] args) throws Exception {
        boolean setJnaNoSys = System.getProperty("jna.nosys") == null;

        List<String> l = new ArrayList<>();
        l.addAll(MillEnv.millLaunchJvmCommand(setJnaNoSys));
        l.add("mill.runner.MillMain");
        l.addAll(Arrays.asList(args));

        Process running = new ProcessBuilder()
            .command(l)
            .inheritIO()
            .start();
        return running.waitFor();
    }
}
