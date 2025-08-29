package mill.main.maven

import mill.main.buildgen.{JavaHomeModuleConfig, JavaModuleConfig}
import org.apache.maven.artifact.versioning.VersionRange
import org.apache.maven.model.{ConfigurationContainer, Model, Plugin}
import org.codehaus.plexus.util.xml.Xpp3Dom

import scala.jdk.CollectionConverters.*
import scala.math.Ordering.Implicits.infixOrderingOps

object Plugins {

  def annotationProcessorMvnDeps(model: Model): Seq[String] =
    mavenCompilerPlugin(model).flatMap(config).fold(Nil)(
      children(_, "annotationProcessorPaths", "path").iterator.flatMap: dom =>
        for
          groupId <- value(dom, "groupId")
          artifactId <- value(dom, "artifactId")
          version = value(dom, "version").orNull
          exclusions = children(dom, "exclusions").iterator.flatMap: dom =>
            for
              groupId <- value(dom, "groupId")
              artifactId <- value(dom, "artifactId")
            yield (groupId, artifactId)
          .toSeq
        yield JavaModuleConfig.mvnDep(groupId, artifactId, version, excludes = exclusions)
      .toSeq
    )

  def javacOptions(model: Model): Seq[String] = {
    val b = Seq.newBuilder[String]
    mavenCompilerPlugin(model).flatMap(config).foreach { dom =>
      // javac requires --release to be mutually exclusive with -source/-target
      value(dom, "release") match {
        case Some(value) => b += "--release" += value
        case None =>
          value(dom, "source").foreach(b += "-source" += _)
          value(dom, "target").foreach(b += "-target" += _)
      }
      value(dom, "encoding").foreach(b += "-encoding" += _)
      b ++= values(dom, "compilerArgs")
    }
    // https://maven.apache.org/configure.html
    val configFile = os.pwd / ".mvn/jvm.config"
    if (os.exists(configFile)) b ++= os.read.lines(configFile).iterator.flatMap(_.split(" "))
    b.result()
  }

  def skipDeploy(model: Model): Boolean =
    mavenDeployPlugin(model).flatMap(config).flatMap(value(_, "skip")).fold(false)(_.toBoolean)

  /**
   * @see [[https://maven.apache.org/enforcer/enforcer-rules/requireJavaVersion.html]]
   */
  def jvmId(model: Model): Option[String] = {
    mavenEnforcerPlugin(model).flatMap(
      _.getExecutions.iterator.asScala.flatMap(config)
        .flatMap(value(_, "rules", "requireJavaVersion", "version"))
        .map(VersionRange.createFromVersionSpec)
        .flatMap: v =>
          Option(v.getRecommendedVersion)
            .orElse(v.getRestrictions.iterator.asScala.collectFirst:
              case r if r.getLowerBound != null => r.getLowerBound)
        .reduceOption(_.min(_))
        .flatMap(v => JavaHomeModuleConfig.jvmId(v.getMinorVersion))
    )
  }

  /**
   * @see [[https://maven.apache.org/plugins/maven-compiler-plugin/index.html]]
   */
  def mavenCompilerPlugin(model: Model): Option[Plugin] =
    plugin(model, "org.apache.maven.plugins", "maven-compiler-plugin")

  /**
   * @see [[https://maven.apache.org/plugins/maven-deploy-plugin/index.html]]
   */
  def mavenDeployPlugin(model: Model): Option[Plugin] =
    plugin(model, "org.apache.maven.plugins", "maven-deploy-plugin")

  /**
   * @see [[https://maven.apache.org/enforcer/maven-enforcer-plugin/index.html]]
   */
  def mavenEnforcerPlugin(model: Model): Option[Plugin] =
    plugin(model, "org.apache.maven.plugins", "maven-enforcer-plugin")

  def plugin(model: Model, groupId: String, artifactId: String): Option[Plugin] =
    model.getBuild.getPlugins.iterator.asScala
      .find(p => p.getGroupId == groupId && p.getArtifactId == artifactId)

  def config(cc: ConfigurationContainer): Option[Xpp3Dom] = cc.getConfiguration match {
    case dom: Xpp3Dom => Some(dom)
    case _ => None
  }

  def children(dom: Xpp3Dom, path: String*): IterableOnce[Xpp3Dom] =
    if (dom == null) Nil
    else if (path.isEmpty) dom.getChildren.iterator
    else dom.getChildren(path.head).flatMap(children(_, path.tail*))

  def child(dom: Xpp3Dom, path: String*): Option[Xpp3Dom] =
    if (dom == null) None
    else if (path.isEmpty) Some(dom)
    else child(dom.getChild(path.head), path.tail*)

  def value(dom: Xpp3Dom, path: String*): Option[String] =
    if (null == dom) None
    else if (path.isEmpty) Option(dom.getValue)
    else value(dom.getChild(path.head), path.tail*)

  def values(dom: Xpp3Dom, path: String*): IterableOnce[String] =
    if (dom == null) Nil
    else if (path.isEmpty) dom.getChildren.iterator.flatMap(dom => Option(dom.getValue))
    else dom.getChildren(path.head).flatMap(values(_, path.tail*))
}
