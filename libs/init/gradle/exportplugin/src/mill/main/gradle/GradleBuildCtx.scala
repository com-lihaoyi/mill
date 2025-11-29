package mill.main.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.CompileOptions

import scala.jdk.CollectionConverters.*
import scala.math.Ordered.orderingToOrdered

/**
 * Gradle-version agnostic build API.
 */
trait GradleBuildCtx {
  def releaseVersion(opts: CompileOptions): Option[Int]
  def project(dep: ProjectDependency): Project
  def sourceSets(javaPluginExt: JavaPluginExtension): Set[SourceSet]
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

    def releaseVersion(opts: CompileOptions) =
      if ((6, 6) <= gradleVersion) opts.getRelease.getOrElse(0).intValue match {
        case 0 => None
        case n => Some(n)
      }
      else None
    def project(dep: ProjectDependency) =
      if ((8, 11) <= gradleVersion) gradle.getRootProject.findProject(dep.getPath)
      else dep.getDependencyProject: @scala.annotation.nowarn("cat=deprecation")
    def sourceSets(javaPluginExt: JavaPluginExtension) =
      if ((7, 1) <= gradleVersion) javaPluginExt.getSourceSets.asScala.toSet
      else Set.empty
  }
}
