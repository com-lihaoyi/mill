package mill.main.client;

import java.io.File;
import java.lang.reflect.Method;
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
                Class<?> millMainClass = IsolatedMillMainLoader.class.getClassLoader().loadClass("mill.MillMain");
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
            String propApplied = System.getProperty("mill.jvm_opts_applied");
            String propOptsFileName = System.getenv("MILL_JVM_OPTS_PATH");
            File propOptsFile;
            if (propOptsFileName == null || propOptsFileName.trim().equals("")) {
                propOptsFile = new File(".mill-jvm-opts");
            } else {
                propOptsFile = new File(propOptsFileName);
            }
            if ((propApplied == null || !propApplied.equals("true")) && propOptsFile.exists()) {
                System.err.println("Warning: Settings from file `" + propOptsFile.getAbsolutePath() + "` are currently ignored.");
            }
            // TODO: check for presence of marker property, if not and we have a settings file, spawn extra JVM
            Method mainMethod = loadResult.millMainMethod.get();
            mainMethod.invoke(null, new Object[]{args});
        } else {
            throw new RuntimeException("Cannot load mill.MillMain class");
        }
    }
}
