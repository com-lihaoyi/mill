package mill.scalanativelib.api;

import sbt.testing.Framework;

public interface ScalaNativeWorkerApi {
    java.io.File discoverClang();
    java.io.File discoverClangPP();
    String[] discoverCompileOptions();
    String[] discoverLinkingOptions();

    NativeConfig config(String mainClass,
                        java.io.File[] classpath,
                        java.io.File nativeWorkdir,
                        java.io.File nativeClang,
                        java.io.File nativeClangPP,
                        java.util.Optional<String> nativeTarget,
                        String[] nativeCompileOptions,
                        String[] nativeLinkingOptions,
                        String nativeGC,
                        boolean nativeLinkStubs,
                        LTO nativeLTO,
                        ReleaseMode releaseMode,
                        boolean optimize,
                        boolean embedResources,
                        boolean incrementalCompilation,
                        NativeLogLevel logLevel);

    String defaultGarbageCollector();
    java.io.File nativeLink(NativeConfig nativeConfig, java.io.File outPath);

    GetFrameworkResult getFramework(java.io.File testBinary,
                                    java.util.Map<String, String> envVars,
                                    NativeLogLevel logLevel,
                                    String frameworkName);
}
