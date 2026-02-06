package mill.main.maven

import mill.main.buildgen.ModuleSpec
import mill.main.buildgen.ModuleSpec.*
import mill.main.maven.MavenUtil.*
import org.apache.maven.model.{ConfigurationContainer, Model}
import org.codehaus.plexus.util.xml.Xpp3Dom

import scala.jdk.CollectionConverters.*
import scala.util.Try

class Plugins(model: Model, mvnWorkspace: os.Path) {

  def javacOptions: Seq[Opt] = plugin("maven-compiler-plugin").flatMap(config).fold(Nil) { dom =>
    def opt(name: String, prefix: String = "-") = value(dom, name).map(Opt(prefix + name, _))
    opt("release", "--").fold(Seq(opt("source"), opt("target")).flatten)(Seq(_)) ++
      opt("encoding") ++
      child(dom, "compilerArgs").fold(Nil)(dom => Opt.groups(values(dom, "arg")))
  }

  def errorProneMvnDeps: Seq[MvnDep] = plugin("maven-compiler-plugin").flatMap(config)
    .filter(child(_, "compilerArgs").exists(
      values(_, "arg").exists(_.startsWith("-Xplugin:ErrorProne"))
    ))
    .flatMap(child(_, "annotationProcessorPaths"))
    .fold(Nil)(children(_, "path"))
    .flatMap(dom =>
      for {
        organization <- value(dom, "groupId")
        name <- value(dom, "artifactId")
        version = value(dom, "version").getOrElse("")
        classifier = value(dom, "classifier")
        typ = value(dom, "type")
        excludes = children(dom, "exclusions").flatMap { dom =>
          for {
            groupId <- value(dom, "groupId")
            artifactId <- value(dom, "artifactId")
          } yield (groupId, artifactId)
        }
      } yield MvnDep(
        organization = organization,
        name = name,
        version = version,
        classifier = classifier,
        `type` = typ,
        excludes = excludes
      )
    )

  def skipDeploy: Boolean = plugin("maven-deploy-plugin").flatMap(config)
    .flatMap(value(_, "skip")).fold(false)(_.toBoolean)

  def testForkArgs: Seq[Opt] = plugin("maven-surefire-plugin").flatMap(config)
    .flatMap(child(_, "systemPropertyVariables")).fold(Nil) { dom =>
      dom.getChildren.toSeq.map { dom =>
        val key = dom.getName
        val value = dom.getValue
        Opt(s"-D$key=$value")
      }
    }

  def withCheckstyleModule(module: ModuleSpec): Option[ModuleSpec] = for {
    plugin0 <- plugin("maven-checkstyle-plugin")
    dom <- plugin0.getExecutions.asScala.find(_.getGoals.contains("check")).flatMap(config)
    propertyExpansion = value(dom, "propertyExpansion")
    checkstyleProperties = propertyExpansion.fold(Nil) { v =>
      v.split("\\s").toSeq.collect {
        case s"$k=$v" => (k, v)
      }
    }
    checkstyleMvnDeps = plugin0.getDependencies.asScala.toSeq.map(toMvnDep)
    moduleDir = os.Path(model.getProjectDirectory)
    checkstyleConfig = value(dom, "configLocation").flatMap(v =>
      Try((mvnWorkspace / os.RelPath(v)).relativeTo(moduleDir)).toOption
    )
  } yield module.withCheckstyleModule(
    checkstyleProperties = Values(checkstyleProperties, appendSuper = true),
    checkstyleMvnDeps = checkstyleMvnDeps,
    checkstyleConfig = checkstyleConfig
  )

  private def plugin(artifactId: String, groupId: String = "org.apache.maven.plugins") =
    model.getBuild.getPlugins.asScala.find(p =>
      p.getArtifactId == artifactId && p.getGroupId == groupId
    )

  private def config(cc: ConfigurationContainer) = cc.getConfiguration match {
    case dom: Xpp3Dom => Some(dom)
    case _ => None
  }

  private def child(dom: Xpp3Dom, name: String): Option[Xpp3Dom] =
    dom.getChild(name) match {
      case null => None
      case dom => Some(dom)
    }

  private def children(dom: Xpp3Dom, name: String): Seq[Xpp3Dom] =
    dom.getChildren(name).toSeq

  private def value(dom: Xpp3Dom, name: String): Option[String] =
    dom.getChild(name) match {
      case null => None
      case dom => Some(dom.getValue)
    }

  private def values(dom: Xpp3Dom, name: String): Seq[String] =
    dom.getChildren(name).toSeq.map(_.getValue)
}
