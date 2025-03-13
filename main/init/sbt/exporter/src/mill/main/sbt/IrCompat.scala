package mill.main.sbt

import mill.main.buildgen._
import sbt.URL
import sbt.librarymanagement._

object IrCompat {

  def crossVersions(scalaVersion: String, crossScalaVersions: Seq[String]) =
    crossScalaVersions match {
      case Seq(`scalaVersion`) => Nil
      case _ => crossScalaVersions
    }

  def toLicense(l: (String, URL)) = {
    val (name, url) = l
    IrLicense(
      id = name,
      name = name,
      url = url.toExternalForm
    )
  }

  def toVersionControl(scm: Option[ScmInfo]) =
    scm.fold(IrVersionControl()) { info =>
      import info._
      IrVersionControl(
        url = Some(browseUrl.toExternalForm),
        connection = Some(connection),
        developerConnection = devConnection,
        tag = None
      )
    }

  def toDeveloper(dev: Developer) = {
    import dev._
    IrDeveloper(
      id = id,
      name = name,
      url = url.toExternalForm
    )
  }

  def toPomSettings(info: ModuleInfo) = {
    import info._
    IrPomSettings(
      description = description,
      organization = organizationName,
      url = homepage.fold("")(_.toExternalForm),
      licenses = licenses.map(toLicense),
      versionControl = toVersionControl(scmInfo),
      developers = developers.map(toDeveloper)
    )
  }

  val repository: PartialFunction[Resolver, String] = {
    case r: MavenRepository => r.root
  }

  def skipDep(moduleID: ModuleID) = {
    val organization = moduleID.organization
    val name = moduleID.name
    organization match {
      case "org.scala-lang" => true
      case "org.scala-js" => name != "scalajs-dom"
      case "org.scala-native" => true
      case _ => false
    }
  }

  class ToDeps(
      libDeps: Seq[ModuleID],
      platformed: Boolean,
      disabled: CrossVersion = CrossVersion.disabled
  ) {

    def apply(configuration: Option[String]) =
      libDeps.iterator
        .filter(m => m.configurations == configuration)
        .map(toDep)
        .toSeq

    def toDep(moduleId: ModuleID) = {
      val crossVersion = moduleId.crossVersion match {
        case `disabled` => IrCrossVersion.Constant(platformed = platformed)
        case _: CrossVersion.Binary => IrCrossVersion.Binary(platformed)
        case _: CrossVersion.Full => IrCrossVersion.Full(platformed)
        case cv: CrossVersion.Constant => IrCrossVersion.Constant(cv.value, platformed)
        case _: For3Use2_13 => IrCrossVersion.Constant("2.13", platformed)
        case _: For2_13Use3 => IrCrossVersion.Constant("3", platformed)
        case cv =>
          println(s"ignoring cross version $cv for dependency $moduleId")
          IrCrossVersion.Constant(platformed = platformed)
      }

      def moduleAttr[T](f: Artifact => T) = moduleId.explicitArtifacts.iterator
        .collectFirst { case a if a.name == moduleId.name => f(a) }

      IrDep(
        organization = moduleId.organization,
        name = moduleId.name,
        version = moduleId.revision,
        crossVersion = crossVersion,
        `type` = moduleAttr(_.`type`),
        classifier = moduleAttr(_.classifier).flatten
      )
    }
  }
}
