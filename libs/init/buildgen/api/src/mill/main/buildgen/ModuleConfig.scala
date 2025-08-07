package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

sealed trait ModuleConfig
object ModuleConfig {
  implicit val rw: ReadWriter[ModuleConfig] = macroRW

  def abstracted(self: Seq[ModuleConfig], that: Seq[ModuleConfig]) = {
    def args(self: Seq[String], that: Seq[String]) =
      if (that.containsSlice(self)) self else if (self.containsSlice(that)) that else Nil
    def value[A](self: A, that: A, default: A = null): A = if (self == that) self else default

    self.flatMap {
      case self: CoursierModuleConfig => that.collectFirst {
          case that: CoursierModuleConfig => CoursierModuleConfig(
              self.repositories.intersect(that.repositories)
            )
        }
      case self: JavaModuleConfig => that.collectFirst {
          case that: JavaModuleConfig => JavaModuleConfig(
              self.mandatoryMvnDeps.intersect(that.mandatoryMvnDeps),
              self.mvnDeps.intersect(that.mvnDeps),
              self.compileMvnDeps.intersect(that.compileMvnDeps),
              self.runMvnDeps.intersect(that.runMvnDeps),
              self.moduleDeps.intersect(that.moduleDeps),
              self.compileModuleDeps.intersect(that.compileModuleDeps),
              self.runModuleDeps.intersect(that.runModuleDeps),
              args(self.javacOptions, that.javacOptions)
            )
        }
      case self: PublishModuleConfig => that.collectFirst {
          case that: PublishModuleConfig => PublishModuleConfig(
              value(self.pomSettings, that.pomSettings),
              value(self.publishVersion, that.publishVersion),
              value(self.versionScheme, that.versionScheme, None)
            )
        }
      case self: ScalaModuleConfig => that.collectFirst {
          case that: ScalaModuleConfig => ScalaModuleConfig(
              value(self.scalaVersion, that.scalaVersion),
              args(self.scalacOptions, that.scalacOptions),
              self.scalacPluginMvnDeps.intersect(that.scalacPluginMvnDeps)
            )
        }
      case self: ScalaJSModuleConfig => that.collectFirst {
          case that: ScalaJSModuleConfig => ScalaJSModuleConfig(
              value(self.scalaJSVersion, that.scalaJSVersion)
            )
        }
      case self: ScalaNativeModuleConfig => that.collectFirst {
          case that: ScalaNativeModuleConfig => ScalaNativeModuleConfig(
              value(self.scalaNativeVersion, that.scalaNativeVersion)
            )
        }
    }
  }

  def inherited(self: Seq[ModuleConfig], that: Seq[ModuleConfig]) = {
    def args(self: Seq[String], base: Seq[String]) = self.indexOfSlice(base) match {
      case -1 => self
      case i => self.take(i) ++ self.drop(i + base.length)
    }
    def value[A](self: A, base: A, default: A = null): A = if (self == base) default else self

    self.map {
      case self: CoursierModuleConfig => that.collectFirst {
          case that: CoursierModuleConfig => CoursierModuleConfig(
              self.repositories.diff(that.repositories)
            )
        }.getOrElse(self)
      case self: JavaModuleConfig => that.collectFirst {
          case that: JavaModuleConfig => JavaModuleConfig(
              self.mandatoryMvnDeps.diff(that.mandatoryMvnDeps),
              self.mvnDeps.diff(that.mvnDeps),
              self.compileMvnDeps.diff(that.compileMvnDeps),
              self.runMvnDeps.diff(that.runMvnDeps),
              self.moduleDeps.diff(that.moduleDeps),
              self.compileModuleDeps.diff(that.compileModuleDeps),
              self.runModuleDeps.diff(that.runModuleDeps),
              args(self.javacOptions, that.javacOptions)
            )
        }.getOrElse(self)
      case self: PublishModuleConfig => that.collectFirst {
          case that: PublishModuleConfig => PublishModuleConfig(
              value(self.pomSettings, that.pomSettings),
              value(self.publishVersion, that.publishVersion),
              value(self.versionScheme, that.versionScheme, None)
            )
        }.getOrElse(self)
      case self: ScalaModuleConfig => that.collectFirst {
          case that: ScalaModuleConfig => ScalaModuleConfig(
              value(self.scalaVersion, that.scalaVersion),
              args(self.scalacOptions, that.scalacOptions),
              self.scalacPluginMvnDeps.diff(that.scalacPluginMvnDeps)
            )
        }.getOrElse(self)
      case self: ScalaJSModuleConfig => that.collectFirst {
          case that: ScalaJSModuleConfig => ScalaJSModuleConfig(
              value(self.scalaJSVersion, that.scalaJSVersion)
            )
        }.getOrElse(self)
      case self: ScalaNativeModuleConfig => that.collectFirst {
          case that: ScalaNativeModuleConfig => ScalaNativeModuleConfig(
              value(self.scalaNativeVersion, that.scalaNativeVersion)
            )
        }.getOrElse(self)
    }
  }
}

case class CoursierModuleConfig(repositories: Seq[String] = Nil) extends ModuleConfig
object CoursierModuleConfig {
  implicit val rw: ReadWriter[CoursierModuleConfig] = macroRW
}

case class JavaModuleConfig(
    mandatoryMvnDeps: Seq[String] = Nil,
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
    pomSettings: PublishModuleConfig.PomSettings = null,
    publishVersion: String = null,
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
    scalaVersion: String = null,
    scalacOptions: Seq[String] = Nil,
    scalacPluginMvnDeps: Seq[String] = Nil
) extends ModuleConfig
object ScalaModuleConfig {
  implicit val rw: ReadWriter[ScalaModuleConfig] = macroRW
}

case class ScalaJSModuleConfig(scalaJSVersion: String = null) extends ModuleConfig
object ScalaJSModuleConfig {
  implicit val rw: ReadWriter[ScalaJSModuleConfig] = macroRW
}

case class ScalaNativeModuleConfig(scalaNativeVersion: String = null) extends ModuleConfig
object ScalaNativeModuleConfig {
  implicit val rw: ReadWriter[ScalaNativeModuleConfig] = macroRW
}
