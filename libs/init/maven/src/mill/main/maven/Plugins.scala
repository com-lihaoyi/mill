package mill.main.maven

import mill.main.buildgen.ModuleSpec.{MvnDep, Opt, Values}
import org.apache.maven.model.{ConfigurationContainer, Model, Resource}
import org.apache.maven.shared.utils.io.FileUtils
import org.codehaus.plexus.util.xml.Xpp3Dom

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

class Plugins(model: Model) {

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
    .flatMap { dom =>
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
    }

  def skipDeploy: Boolean = plugin("maven-deploy-plugin").flatMap(config)
    .flatMap(value(_, "skip")).fold(false)(_.toBoolean)

  def sources: (
      Values[os.SubPath],
      Values[os.RelPath],
      Values[os.RelPath],
      Values[os.SubPath],
      Values[os.RelPath],
      Values[os.RelPath]
  ) = {
    val helperExecutions = plugin("build-helper-maven-plugin", "org.codehaus.mojo").toSeq
      .flatMap(_.getExecutions.asScala)
    def helperConfigs(goal: String) =
      helperExecutions.filter(_.getGoals.contains(goal)).flatMap(config)
    val moduleDir = os.Path(model.getProjectDirectory)
    val buildDir = os.Path(model.getBuild.getDirectory)
    def scan(dir: os.Path, includes: Iterable[String], excludes: Iterable[String]) = {
      if (dir.startsWith(buildDir)) Nil
      else if (includes.isEmpty && excludes.isEmpty || !os.exists(dir)) Seq(dir)
      else FileUtils.getFileNames(
        dir.toIO,
        if (includes.isEmpty) null else includes.mkString(","),
        if (excludes.isEmpty) null else excludes.mkString(","),
        true
      ).asScala.toSeq.map(os.Path(_))
    }
    def sources(defaultDir: String, helperConfigs: Seq[Xpp3Dom]) =
      helperConfigs.flatMap(child(_, "sources")).flatMap(values(_, "source")).map {
        str =>
          val jPath = Path.of(str)
          if (jPath.isAbsolute) os.Path(jPath) else moduleDir / os.RelPath(jPath)
      }.prepended(os.Path(defaultDir)).distinct.collect {
        case path if !path.startsWith(buildDir) => path.relativeTo(moduleDir)
      }
    def resources(defaults: Seq[Resource], helperConfigs: Seq[Xpp3Dom]) = defaults.flatMap { res =>
      import res.*
      if (Option(getFiltering).exists(_.toBoolean)) Nil
      else scan(os.Path(getDirectory), getIncludes.asScala, getExcludes.asScala)
    }.concat(
      helperConfigs.flatMap(child(_, "resources")).flatMap(children(_, "resource")).flatMap {
        dom =>
          value(dom, "directory").fold(Nil) { str =>
            val dir = Path.of(str) match {
              case path if path.isAbsolute => os.Path(path)
              case path => moduleDir / os.RelPath(path)
            }
            val includes = child(dom, "includes").fold(Nil)(values(_, "include"))
            val excludes = child(dom, "excludes").fold(Nil)(values(_, "exclude"))
            scan(dir, includes, excludes)
          }
      }
    ).distinct.map(_.relativeTo(moduleDir))
    val (mainSourcesFolders, mainSources) =
      sources(model.getBuild.getSourceDirectory, helperConfigs("add-source")).partition(_.ups == 0)
    val mainResources =
      resources(model.getBuild.getResources.asScala.toSeq, helperConfigs("add-resource"))
    val (testSourcesFolders, testSources) =
      sources(model.getBuild.getTestSourceDirectory, helperConfigs("add-test-source"))
        .partition(_.ups == 0)
    val testResources =
      resources(model.getBuild.getTestResources.asScala.toSeq, helperConfigs("add-test-resource"))
    (
      if (mainSourcesFolders == Seq(os.rel / "src/main/java")) Nil
      else mainSourcesFolders.map(_.asSubPath),
      Values(mainSources, appendSuper = true),
      if (mainResources == Seq(os.rel / "src/main/resources")) Nil else mainResources,
      if (testSourcesFolders == Seq(os.rel / "src/test/java")) Nil
      else testSourcesFolders.map(_.asSubPath),
      Values(testSources, appendSuper = true),
      if (testResources == Seq(os.rel / "src/test/resources")) Nil else testResources
    )
  }

  def testForkArgs: Seq[Opt] = plugin("maven-surefire-plugin").flatMap(config).fold(Nil) { dom =>
    children(dom, "systemPropertyVariables").map { dom =>
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
}
