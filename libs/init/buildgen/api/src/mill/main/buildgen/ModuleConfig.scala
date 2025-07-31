package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

sealed trait ModuleConfig
object ModuleConfig {
  implicit val rw: ReadWriter[ModuleConfig] = macroRW
}

case class CoursierModuleConfig(repositories: Seq[String] = Nil) extends ModuleConfig
object CoursierModuleConfig {
  implicit val rw: ReadWriter[CoursierModuleConfig] = macroRW
}

case class JavaModuleConfig(
    mvnDeps: Seq[String] = Nil,
    compileMvnDeps: Seq[String] = Nil,
    runMvnDeps: Seq[String] = Nil,
    moduleDeps: Seq[JavaModuleConfig.ModuleDep] = Nil,
    compileModuleDeps: Seq[JavaModuleConfig.ModuleDep] = Nil,
    runModuleDeps: Seq[JavaModuleConfig.ModuleDep] = Nil,
    javacOptions: Seq[String] = Nil
) extends ModuleConfig
object JavaModuleConfig {
  case class ModuleDep(segments: Seq[String], crossArgs: Map[Int, String] = Map())
  object ModuleDep {
    implicit val rw: ReadWriter[ModuleDep] = macroRW
  }
  implicit val rw: ReadWriter[JavaModuleConfig] = macroRW
}

case class PublishModuleConfig(
    pomSettings: PublishModuleConfig.PomSettings,
    publishVersion: String,
    versionScheme: Option[String] = None
) extends ModuleConfig
object PublishModuleConfig {
  case class Artifact(group: String, id: String, verison: String)
  object Artifact {
    implicit val rw: ReadWriter[Artifact] = macroRW
  }
  case class License(
      id: String,
      name: String,
      url: String = "",
      isOsiApproved: Boolean = false,
      isFsfLibre: Boolean = false,
      distribution: String = ""
  )
  object License {
    implicit val rw: ReadWriter[License] = macroRW
  }
  case class VersionControl(
      browsableRepository: Option[String] = None,
      connection: Option[String] = None,
      developerConnection: Option[String] = None,
      tag: Option[String] = None
  )
  object VersionControl {
    implicit val rw: ReadWriter[VersionControl] = macroRW
  }
  case class Developer(
      id: String,
      name: String,
      url: String,
      organization: Option[String] = None,
      organizationUrl: Option[String] = None
  )
  object Developer {
    implicit val rw: ReadWriter[Developer] = macroRW
  }
  case class PomSettings(
      description: String,
      organization: String,
      url: String,
      licenses: Seq[License] = Nil,
      versionControl: VersionControl = VersionControl(),
      developers: Seq[Developer] = Nil
  )
  object PomSettings {
    implicit val rw: ReadWriter[PomSettings] = macroRW
  }
  sealed trait VersionScheme
  object VersionScheme {
    case object Early
  }

  implicit val rw: ReadWriter[PublishModuleConfig] = macroRW
}

case class ScalaModuleConfig(
    scalaVersion: String = "",
    scalacOptions: Seq[String] = Nil,
    scalacPluginMvnDeps: Seq[String] = Nil
) extends ModuleConfig
object ScalaModuleConfig {
  implicit val rw: ReadWriter[ScalaModuleConfig] = macroRW
}

case class ScalaJSModuleConfig(scalaJSVersion: String = "") extends ModuleConfig
object ScalaJSModuleConfig {
  implicit val rw: ReadWriter[ScalaJSModuleConfig] = macroRW
}

case class ScalaNativeModuleConfig(scalaNativeVersion: String = "") extends ModuleConfig
object ScalaNativeModuleConfig {
  implicit val rw: ReadWriter[ScalaNativeModuleConfig] = macroRW
}
