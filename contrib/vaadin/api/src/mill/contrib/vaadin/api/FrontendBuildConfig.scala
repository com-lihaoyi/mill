package mill.contrib.vaadin.api

import java.nio.file.Path

/**
 * Inputs of one production frontend build. All paths are absolute.
 *
 * @param projectDir npm folder: `package.json`, `node_modules`, Vite config
 * @param frontendDir frontend sources (themes, styles, TypeScript views)
 * @param javaSourceDir main JVM source folder
 * @param resourcesDir main resources folder, holding `application.properties`
 * @param buildToolsDir Vaadin's build folder (Maven: `target`, Gradle: `build`)
 * @param stageDir output root; servlet resources are written to `META-INF/VAADIN` below it
 * @param classpath classpath scanned for frontend dependencies (`@JsModule`, `@NpmPackage`, ...)
 * @param applicationIdentifier identifier of the application's frontend bundle
 */
case class FrontendBuildConfig(
    projectDir: Path,
    frontendDir: Path,
    javaSourceDir: Path,
    resourcesDir: Path,
    buildToolsDir: Path,
    stageDir: Path,
    classpath: Seq[Path],
    applicationIdentifier: String
)
