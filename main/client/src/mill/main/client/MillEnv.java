package mill.main.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

public class MillEnv {

    static File millJvmOptsFile() {
        String millJvmOptsPath = System.getenv("MILL_JVM_OPTS_PATH");
        if (millJvmOptsPath == null || millJvmOptsPath.trim().equals("")) {
            millJvmOptsPath = ".mill-jvm-opts";
        }
        return new File(millJvmOptsPath).getAbsoluteFile();
    }

    static boolean millJvmOptsAlreadyApplied() {
        final String propAppliedProp = System.getProperty("mill.jvm_opts_applied");
        return propAppliedProp != null && propAppliedProp.equals("true");
    }

    static boolean isWin() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    static String javaExe() {
        final String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
            final File exePath = new File(
                javaHome + File.separator +
                    "bin" + File.separator +
                    "java" + (isWin() ? ".exe" : "")
            );
            if (exePath.exists()) {
                return exePath.getAbsolutePath();
            }
        }
        return "java";
    }

    static String[] millClasspath() {
        String selfJars = "";
        List<String> vmOptions = new LinkedList<>();
        String millOptionsPath = System.getProperty("MILL_OPTIONS_PATH");
        if (millOptionsPath != null) {

            // read MILL_CLASSPATH from file MILL_OPTIONS_PATH
            Properties millProps = new Properties();
            try (FileInputStream is = new FileInputStream(millOptionsPath)) {
                millProps.load(is);
            } catch (IOException e) {
                throw new RuntimeException("Could not load '" + millOptionsPath + "'", e);
            }

            for (final String k : millProps.stringPropertyNames()) {
                String propValue = millProps.getProperty(k);
                if ("MILL_CLASSPATH".equals(k)) {
                    selfJars = propValue;
                }
            }
        } else {
            // read MILL_CLASSPATH from file sys props
            selfJars = System.getProperty("MILL_CLASSPATH");
        }

        if (selfJars == null || selfJars.trim().isEmpty()) {
            // We try to use the currently local classpath as MILL_CLASSPATH
            selfJars = System.getProperty("java.class.path").replace(File.pathSeparator, ",");
        }

        if (selfJars == null || selfJars.trim().isEmpty()) {
            throw new RuntimeException("MILL_CLASSPATH is empty!");
        }
        return selfJars.split("[,]");
    }

    static List<String> millLaunchJvmCommand(boolean setJnaNoSys) {
        final List<String> vmOptions = new ArrayList<>();

        // Java executable
        vmOptions.add(javaExe());

        // jna
        if (setJnaNoSys) {
            vmOptions.add("-Djna.nosys=true");
        }

        // sys props
        final Properties sysProps = System.getProperties();
        for (final String k : sysProps.stringPropertyNames()) {
            if (k.startsWith("MILL_") && !"MILL_CLASSPATH".equals(k)) {
                vmOptions.add("-D" + k + "=" + sysProps.getProperty(k));
            }
        }

        // extra opts
        File millJvmOptsFile = millJvmOptsFile();
        if (millJvmOptsFile.exists()) {
            vmOptions.addAll(readMillJvmOpts());
        }

        vmOptions.add("-cp");
        vmOptions.add(String.join(File.pathSeparator, millClasspath()));

        return vmOptions;
    }

    static List<String> readMillJvmOpts() {
        final List<String> vmOptions = new LinkedList<>();
        try (
            final Scanner sc = new Scanner(millJvmOptsFile())
        ) {
            while (sc.hasNextLine()) {
                String arg = sc.nextLine();
                if (!arg.trim().isEmpty() && !arg.startsWith("#")) {
                    vmOptions.add(arg);
                }
            }
        } catch (FileNotFoundException e) {
            // ignored
        }
        return vmOptions;
    }

}
