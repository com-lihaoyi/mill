package mill.javalib

import mill.api.Task
import mill.javalib.publish.Dependency

/**
 * A module trait that combines shading support with publishing capabilities.
 *
 * When publishing, this module:
 * - Includes shaded classes directly in the published JAR
 * - Removes shaded dependencies from the POM/ivy.xml metadata
 *
 * This ensures that consumers of your published artifact:
 * - Get the shaded classes bundled in your JAR
 * - Don't pull in the original (unshaded) dependencies transitively
 *
 * Example usage:
 * {{{
 * object myLib extends JavaModule with ShadingPublishModule {
 *   def shadedMvnDeps = Seq(
 *     mvn"com.google.guava:guava:32.1.3-jre"
 *   )
 *
 *   def shadeRelocations = Seq(
 *     ("com.google.**", "mylib.shaded.google.@1")
 *   )
 *
 *   def publishVersion = "1.0.0"
 *   def pomSettings = PomSettings(...)
 * }
 * }}}
 *
 * The shaded JAR contents are automatically included in the published JAR
 * because [[ShadingModule]] overrides [[ShadingModule.jar]] to merge
 * [[ShadingModule.shadedJar]] contents into the output JAR.
 */
trait ShadingPublishModule extends ShadingModule with PublishModule {

  /**
   * Filter shaded dependencies from the published POM/ivy.xml.
   *
   * Dependencies listed in [[shadedMvnDeps]] and their transitives are removed
   * from the published metadata since they are bundled directly in the JAR.
   */
  override def publishXmlDeps: Task[Seq[Dependency]] = Task.Anon {
    val allDeps = super.publishXmlDeps()

    if (shadedMvnDeps().isEmpty) allDeps
    else {
      // Get all shaded artifact coordinates (including transitives) from resolution
      val shaded = shadedArtifacts()
      val shadedCoordinates = shaded.map(a => (a.group, a.id))

      allDeps.filterNot { dep =>
        shadedCoordinates.contains((dep.artifact.group, dep.artifact.id))
      }
    }
  }
}
