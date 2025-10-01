package mill.main.maven

import mill.main.buildgen.ModuleConfig.MvnDep
import org.apache.maven.artifact.versioning.VersionRange
import org.apache.maven.model.{ConfigurationContainer, Model, Plugin}
import org.codehaus.plexus.util.xml.Xpp3Dom

import scala.jdk.CollectionConverters.*

object Plugins {

  def javacOptions(model: Model): Seq[String] =
    mavenCompilerPlugin(model).flatMap(dom).fold(Nil) { dom =>
      def opts(name: String) = domValue(dom, name).fold(Nil)(Seq(s"-$name", _))
      val opts0 =
        domValue(dom, "release").fold(opts("source") ++ opts("target"))(Seq("--release", _))
      opts0 ++ opts("encoding") ++ domValues(dom, "compilerArgs")
    }

  def javacAnnotationProcessorMvnDeps(model: Model): Seq[MvnDep] =
    mavenCompilerPlugin(model).flatMap(dom).fold(Nil)(
      domChildren(_, "annotationProcessorPaths", "path")
        .flatMap(dom =>
          for {
            groupId <- domValue(dom, "groupId")
            artifactId <- domValue(dom, "artifactId")
            version = domValue(dom, "version")
            exclusions = domChildren(dom, "exclusions").flatMap(dom =>
              for {
                groupId <- domValue(dom, "groupId")
                artifactId <- domValue(dom, "artifactId")
              } yield (groupId, artifactId)
            )
          } yield MvnDep(
            organization = groupId,
            name = artifactId,
            version = version,
            excludes = exclusions
          )
        )
    )

  def skipDeploy(model: Model): Boolean =
    mavenDeployPlugin(model).flatMap(dom).flatMap(domValue(_, "skip")).fold(false)(_.toBoolean)

  /**
   * @see [[https://maven.apache.org/enforcer/enforcer-rules/requireJavaVersion.html]]
   */
  def javaVersion(model: Model): Option[Int] = {
    mavenEnforcerPlugin(model).flatMap(
      _.getExecutions.iterator.asScala
        .filter(_.getGoals.contains("enforce"))
        .flatMap(dom)
        .flatMap(domValue(_, "rules", "requireJavaVersion", "version"))
        .map(VersionRange.createFromVersionSpec)
        .flatMap(v =>
          Option(v.getRecommendedVersion).orElse(
            v.getRestrictions.asScala.headOption.flatMap(r =>
              Option(r.getUpperBound).orElse(Option(r.getLowerBound))
            )
          )
        )
        .maxOption
        .map(_.getMajorVersion)
    )
  }

  /**
   * @see [[https://maven.apache.org/plugins/maven-compiler-plugin/index.html]]
   */
  def mavenCompilerPlugin(model: Model): Option[Plugin] =
    findPlugin(model, "org.apache.maven.plugins", "maven-compiler-plugin")

  /**
   * @see [[https://maven.apache.org/plugins/maven-deploy-plugin/index.html]]
   */
  def mavenDeployPlugin(model: Model): Option[Plugin] =
    findPlugin(model, "org.apache.maven.plugins", "maven-deploy-plugin")

  /**
   * @see [[https://maven.apache.org/enforcer/maven-enforcer-plugin/index.html]]
   */
  def mavenEnforcerPlugin(model: Model): Option[Plugin] =
    findPlugin(model, "org.apache.maven.plugins", "maven-enforcer-plugin")

  def findPlugin(model: Model, groupId: String, artifactId: String): Option[Plugin] =
    model.getBuild.getPlugins.iterator.asScala
      .find(p => p.getGroupId == groupId && p.getArtifactId == artifactId)

  def dom(cc: ConfigurationContainer): Option[Xpp3Dom] = cc.getConfiguration match {
    case dom: Xpp3Dom => Some(dom)
    case _ => None
  }

  def domChildren(dom: Xpp3Dom, names: String*): Seq[Xpp3Dom] =
    if (dom == null) Nil
    else if (names.isEmpty) dom.getChildren.toSeq
    else dom.getChildren(names.head).toSeq.flatMap(domChildren(_, names.tail*))

  def domChild(dom: Xpp3Dom, names: String*): Option[Xpp3Dom] =
    if (dom == null) None
    else if (names.isEmpty) Some(dom)
    else domChild(dom.getChild(names.head), names.tail*)

  def domValue(dom: Xpp3Dom, names: String*): Option[String] =
    if (null == dom) None
    else if (names.isEmpty) Option(dom.getValue)
    else domValue(dom.getChild(names.head), names.tail*)

  def domValues(dom: Xpp3Dom, names: String*): Seq[String] =
    if (dom == null) Nil
    else if (names.isEmpty) dom.getChildren.iterator.flatMap(dom => Option(dom.getValue)).toSeq
    else dom.getChildren(names.head).toSeq.flatMap(domValues(_, names.tail*))
}
