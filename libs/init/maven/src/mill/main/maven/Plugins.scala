package mill.main.maven

import mill.main.buildgen.ModuleSpec.*
import org.apache.maven.model.{ConfigurationContainer, Model}
import org.codehaus.plexus.util.xml.Xpp3Dom

import scala.jdk.CollectionConverters.*

class Plugins(model: Model) {
  private lazy val compilerConfig = plugin("maven-compiler-plugin").flatMap(config)

  private def isErrorProneOption(arg: String): Boolean = arg.startsWith("-Xplugin:ErrorProne")

  private def hasErrorProne(dom: Xpp3Dom): Boolean =
    child(dom, "compilerArgs").exists(values(_, "arg").exists(isErrorProneOption))

  def javacOptions: Seq[Opt] = compilerConfig.fold(Nil) { dom =>
    def opt(name: String, prefix: String = "-") = value(dom, name).map(Opt(prefix + name, _))
    opt("release", "--").fold(Seq(opt("source"), opt("target")).flatten)(Seq(_)) ++
      opt("encoding") ++
      child(dom, "compilerArgs").fold(Nil)(dom =>
        Opt.groups(
          values(dom, "arg").filterNot(arg => isManagedJavacOption(arg) || isErrorProneOption(arg))
        )
      )
  }

  def isErrorProneEnabled: Boolean = compilerConfig.exists(hasErrorProne)

  private lazy val allAnnotationProcessors: Seq[MvnDep] =
    compilerConfig.fold(Nil)(annotationProcessorPaths)

  private def isErrorProneCore(dep: MvnDep): Boolean =
    dep.organization == "com.google.errorprone" && dep.name == "error_prone_core"

  def errorProneMvnDeps: Seq[MvnDep] =
    allAnnotationProcessors.filter(isErrorProneCore)

  def annotationProcessorsMvnDeps: Seq[MvnDep] =
    allAnnotationProcessors.filterNot(isErrorProneCore)

  def errorProneOptions: Seq[String] = (for {
    dom <- compilerConfig.toSeq
    epArg <- child(dom, "compilerArgs").toSeq.flatMap(values(_, "arg").find(isErrorProneOption))
    epArgs = epArg.split("\\s+").toSeq.tail
    // https://errorprone.info/docs/flags#maven
    options = epArgs.collectFirst {
      case arg if arg.head == '@' =>
        os.read(os.Path(arg.tail)).split("\\s+").toSeq
    }.getOrElse(epArgs)
    option <- options
  } yield option).distinct

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

  private def annotationProcessorPaths(dom: Xpp3Dom): Seq[MvnDep] = {
    child(dom, "annotationProcessorPaths")
      .fold(Nil)(children(_, "path"))
      .flatMap { dom =>
        for {
          organization <- value(dom, "groupId")
          name <- value(dom, "artifactId")
          version = value(dom, "version").getOrElse("")
          classifier = value(dom, "classifier")
          _type = value(dom, "type")
          excludes = children(dom, "exclusions").flatMap(children(_, "exclusion")).flatMap { dom =>
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
          `type` = _type,
          excludes = excludes
        )
      }
  }
}
