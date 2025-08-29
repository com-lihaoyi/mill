package mill.main.gradle

import mill.main.buildgen.JavaHomeModuleConfig
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.util.internal.VersionNumber

import scala.math.Ordered.orderingToOrdered

trait GradleBuildContext {
  def jvmId(project: Project): Option[String]
  def project(dep: ProjectDependency): Project
}
object GradleBuildContext {

  def apply(gradle: Gradle): GradleBuildContext = {
    val version = VersionNumber.parse(gradle.getGradleVersion)
    val version_8_11 = VersionNumber.version(8, 11)
    val version_6_7 = VersionNumber.version(6, 7)
    new GradleBuildContext {
      def jvmId(project: Project) =
        if (version < version_6_7) None
        else Option(project.getExtensions.findByType(classOf[JavaPluginExtension]))
          .flatMap: ext =>
            Option(ext.getToolchain)
          .flatMap: tc =>
            Option(tc.getLanguageVersion.getOrNull())
          .flatMap: v =>
            JavaHomeModuleConfig.jvmId(v.asInt())
      def project(dep: ProjectDependency) =
        if (version < version_8_11)
          dep.getDependencyProject: @scala.annotation.nowarn("cat=deprecation")
        else gradle.getRootProject.findProject(dep.getPath)
    }
  }
}
