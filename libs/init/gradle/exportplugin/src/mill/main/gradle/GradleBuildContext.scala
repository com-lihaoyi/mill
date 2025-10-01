package mill.main.gradle

import mill.main.buildgen.JavaHomeModuleConfig
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.CompileOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion

import scala.math.Ordered.orderingToOrdered

trait GradleBuildContext {
  def javaVersion(ext: JavaPluginExtension): Option[Int]
  def releaseVersion(opts: CompileOptions): Option[Int]
  def project(dep: ProjectDependency): Project
}
object GradleBuildContext {

  def apply(gradle: Gradle): GradleBuildContext = Impl(gradle)

  private class Impl(gradle: Gradle) extends GradleBuildContext {
    val gradleVersion = {
      // We replicate the parsing in `org.gradle.util.internal.VersionNumber`.
      // The class is not used here since it was moved, from `org.gradle.util`, in Gradle 7.1.
      // https://github.com/gradle/gradle/commit/a96d4e2ad8cda157233bc6da4b84dc191e9796cf
      val regex = "^(\\d+)[.](\\d+)?.*$".r
      gradle.getGradleVersion match {
        case regex(major, minor) => (major.toInt, Option(minor).fold(0)(_.toInt))
      }
    }

    def javaVersion(ext: JavaPluginExtension) =
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
