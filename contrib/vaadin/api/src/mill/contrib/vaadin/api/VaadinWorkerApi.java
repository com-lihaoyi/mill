package mill.contrib.vaadin.api;

import java.nio.file.Path;

/**
 * Runs Vaadin's production frontend build. Implemented by the
 * {@code mill-contrib-vaadin-worker} artifact, which is loaded in its own
 * classloader together with Vaadin's build tooling; only this package is shared
 * with the build's classloader.
 */
public interface VaadinWorkerApi extends AutoCloseable {

  interface Logger {
    void error(String msg);

    void warn(String msg);

    void info(String msg);

    void debug(String msg);
  }

  /** Inputs of one frontend build. All paths are absolute. */
  interface Config {
    /** npm folder: {@code package.json}, {@code node_modules}, vite config. */
    Path projectDir();

    /** Custom frontend sources (themes, styles, TypeScript views). */
    Path frontendDir();

    /** Main JVM source folder. */
    Path javaSourceDir();

    /** Main resources folder, holding {@code application.properties}. */
    Path resourcesDir();

    /** Vaadin's build folder (Maven: {@code target}, Gradle: {@code build}). */
    Path buildToolsDir();

    /** Output root; servlet resources are written to {@code META-INF/VAADIN} below it. */
    Path stageDir();

    /** Classpath scanned for frontend dependencies ({@code @JsModule}, {@code @NpmPackage}, ...). */
    Path[] classpath();

    /** Identifier of the application's frontend bundle. */
    String applicationIdentifier();
  }

  void buildFrontend(Config config, Logger log);

  @Override
  default void close() {}
}
