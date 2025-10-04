package mill.main.gradle

import mill.main.buildgen.JavaHomeModuleConfig
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.CompileOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion

import scala.math.Ordered.orderingToOrdered

/**
 * Gradle-version agnostic build API.
 */
trait GradleBuildCtx {
  def javaVersion(ext: JavaPluginExtension): Option[Int]
  def releaseVersion(opts: CompileOptions): Option[Int]
  def project(dep: ProjectDependency): Project
}
object GradleBuildCtx {

  def apply(gradle: Gradle): GradleBuildCtx = Impl(gradle)

  private class Impl(gradle: Gradle) extends GradleBuildCtx {
    val gradleVersion = {
      val regex = "^(\\d+)[.](\\d+)?.*$".r
      gradle.getGradleVersion match {
        case regex(major, minor) => (major.toInt, Option(minor).fold(0)(_.toInt))
      }
    }

    def javaVersion(ext: JavaPluginExtension) =
      // When not explicitly configured, the toolchain defaults to the JVM used to run the daemon.
      if ((6, 7) <= gradleVersion) Option(ext.getToolchain)
        .flatMap(tc => Option(tc.getLanguageVersion.getOrNull()))
        .map(_.asInt())
      else None
    def releaseVersion(opts: CompileOptions) =
      if ((6, 6) <= gradleVersion) opts.getRelease.getOrElse(0).intValue match {
        case 0 => None
        case n => Some(n)
      }
      else None
    def project(dep: ProjectDependency) =
      if ((8, 11) <= gradleVersion) gradle.getRootProject.findProject(dep.getPath)
      else dep.getDependencyProject: @scala.annotation.nowarn("cat=deprecation")
  }
}
