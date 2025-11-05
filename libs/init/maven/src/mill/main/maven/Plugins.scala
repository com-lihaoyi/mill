package mill.main.maven

import mill.main.buildgen.ModuleConfig
import org.apache.maven.model.{ConfigurationContainer, Model, Plugin}
import org.codehaus.plexus.util.xml.Xpp3Dom

import scala.jdk.CollectionConverters.*

class Plugins(model: Model) {

  def javacAnnotationProcessorMvnDeps: Seq[ModuleConfig.MvnDep] =
    mavenCompilerPlugin.flatMap(configDom).fold(Nil)(
      children(_, "annotationProcessorPaths", "path")
        .flatMap(dom =>
          for {
            groupId <- value(dom, "groupId")
            artifactId <- value(dom, "artifactId")
            version = value(dom, "version")
            exclusions = children(dom, "exclusions").flatMap(dom =>
              for {
                groupId <- value(dom, "groupId")
                artifactId <- value(dom, "artifactId")
              } yield (groupId, artifactId)
            )
          } yield ModuleConfig.MvnDep(
            organization = groupId,
            name = artifactId,
            version = version,
            excludes = exclusions
          )
        )
    )

  def javacOptions: Seq[String] =
    mavenCompilerPlugin.flatMap(configDom).fold(Nil) { dom =>
      def opts(name: String) = value(dom, name).fold(Nil)(Seq(s"-$name", _))
      val opts0 = value(dom, "release").filter(_.nonEmpty).fold(
        opts("source") ++ opts("target")
      )(Seq("--release", _))
      opts0 ++ opts("encoding") ++ values(dom, "compilerArgs")
    }

  def skipDeploy: Boolean =
    mavenDeployPlugin.flatMap(configDom).flatMap(value(_, "skip")).fold(false)(_.toBoolean)

  /**
   * @see [[https://maven.apache.org/plugins/maven-compiler-plugin/index.html]]
   */
  def mavenCompilerPlugin: Option[Plugin] =
    findPlugin("org.apache.maven.plugins", "maven-compiler-plugin")

  /**
   * @see [[https://maven.apache.org/plugins/maven-deploy-plugin/index.html]]
   */
  def mavenDeployPlugin: Option[Plugin] =
    findPlugin("org.apache.maven.plugins", "maven-deploy-plugin")

  def findPlugin(groupId: String, artifactId: String): Option[Plugin] =
    model.getBuild.getPlugins.asScala
      .find(p => p.getGroupId == groupId && p.getArtifactId == artifactId)

  def configDom(cc: ConfigurationContainer): Option[Xpp3Dom] = cc.getConfiguration match {
    case dom: Xpp3Dom => Some(dom)
    case _ => None
  }

  def children(dom: Xpp3Dom, names: String*): Seq[Xpp3Dom] =
    if (dom == null) Nil
    else if (names.isEmpty) dom.getChildren.toSeq
    else dom.getChildren(names.head).toSeq.flatMap(children(_, names.tail*))

  def value(dom: Xpp3Dom, names: String*): Option[String] =
    if (null == dom) None
    else if (names.isEmpty) value0(dom)
    else value(dom.getChild(names.head), names.tail*)

  def values(dom: Xpp3Dom, names: String*): Seq[String] =
    if (dom == null) Nil
    else if (names.isEmpty) dom.getChildren.toSeq.flatMap(value0)
    else dom.getChildren(names.head).toSeq.flatMap(values(_, names.tail*))

  def value0(dom: Xpp3Dom): Option[String] = dom.getValue match {
    case null | "" => None
    // This could happen for a BOM module that references a property defined in the root POM.
    case s"$${$prop}" => Option(model.getProperties.getProperty(prop))
    case value => Some(value)
  }
}
