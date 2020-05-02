package mill.scalanativelib.api;
import sbt.testing.Framework;

public interface ScalaNativeWorkerApi {
    java.io.File discoverClang();
    java.io.File discoverClangPP();
    String discoverTarget(java.io.File clang, java.io.File workDir);
    String[] discoverCompileOptions();
    String[] discoverLinkingOptions();

    NativeConfig config(java.io.File nativeLibJar,
                        String mainClass,
                        java.io.File[] classpath,
                        java.io.File nativeWorkdir,
                        java.io.File nativeClang,
                        java.io.File nativeClangPP,
                        String nativeTarget,
                        String[] nativeCompileOptions,
                        String[] nativeLinkingOptions,
                        String nativeGC,
                        boolean nativeLinkStubs,
                        ReleaseMode releaseMode,
                        NativeLogLevel logLevel);

    String defaultGarbageCollector();
    java.io.File nativeLink(NativeConfig nativeConfig, java.io.File outPath);

    Framework newScalaNativeFrameWork(Framework framework, int id,
                                      java.io.File testBinary,
                                      NativeLogLevel logLevel,
                                      java.util.Map<String, String> envVars);
    }
