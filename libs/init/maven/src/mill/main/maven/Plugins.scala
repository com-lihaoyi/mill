package mill.main.maven

import mill.main.buildgen.ModuleSpec.{MvnDep, Opt}
import org.apache.maven.model.Model
import org.codehaus.plexus.util.xml.Xpp3Dom

import scala.jdk.CollectionConverters.*
import org.apache.maven.model.Plugin

class Plugins(model: Model) {

  def javacOptions: Seq[Opt] = config(_.getArtifactId == "maven-compiler-plugin").fold(Nil) { dom =>
    value(dom, "release").fold(Seq(
      value(dom, "source").map(Opt("-source", _)),
      value(dom, "target").map(Opt("-target", _))
    ).flatten)(s => Seq(Opt("--release", s))) ++
      value(dom, "encoding").map(Opt("-encoding", _)) ++
      Opt.groups(values(dom, "compilerArgs"))
  }

  def errorProneMvnDeps: Seq[MvnDep] = config(_.getArtifactId == "maven-compiler-plugin")
    .filter(values(_, "compilierArgs").exists(_.startsWith("-Xplugin:ErrorProne")))
    .fold(Nil)(children(_, "annotationProcessorPaths", "path").flatMap { dom =>
      for {
        organization <- value(dom, "groupId")
        name <- value(dom, "artifactId")
        version = value(dom, "version").getOrElse("")
        excludes = children(dom, "exclusions").flatMap { dom =>
          for {
            groupId <- value(dom, "groupId")
            artifactId <- value(dom, "artifactId")
          } yield (groupId, artifactId)
        }
      } yield MvnDep(organization, name, version, excludes = excludes)
    })

  def skipDeploy: Boolean = config(_.getArtifactId == "maven-deploy-plugin")
    .flatMap(value(_, "skip")).fold(false)(_.toBoolean)

  def testForkArgs: Seq[Opt] = config(_.getArtifactId == "maven-surefire-plugin").fold(Nil)(
    children(_, "systemPropertyVariables").map { dom =>
      val key = dom.getName
      val value = dom.getValue
      Opt(s"-D$key=$value")
    }
  )

  private def config(p: Plugin => Boolean): Option[Xpp3Dom] =
    model.getBuild.getPlugins.asScala.find(p).flatMap(_.getConfiguration match {
      case dom: Xpp3Dom => Some(dom)
      case _ => None
    })

  private def children(dom: Xpp3Dom, names: String*): Seq[Xpp3Dom] =
    if (dom == null) Nil
    else if (names.isEmpty) dom.getChildren.toSeq
    else dom.getChildren(names.head).toSeq.flatMap(children(_, names.tail*))

  private def value(dom: Xpp3Dom, names: String*): Option[String] =
    if (null == dom) None
    else if (names.isEmpty) Option(dom.getValue)
    else value(dom.getChild(names.head), names.tail*)

  private def values(dom: Xpp3Dom, names: String*): Seq[String] =
    if (dom == null) Nil
    else if (names.isEmpty) dom.getChildren.toSeq.flatMap(dom => Option(dom.getValue))
    else dom.getChildren(names.head).toSeq.flatMap(values(_, names.tail*))
}
