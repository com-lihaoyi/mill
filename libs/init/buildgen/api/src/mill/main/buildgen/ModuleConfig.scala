package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

/**
 * ADT, intended for code generation, that defines configuration settings for a build module.
 */
sealed trait ModuleConfig
object ModuleConfig {

  /**
   * Computes the shared configurations for a pair of configuration lists.
   */
  def abstracted(self: Seq[ModuleConfig], that: Seq[ModuleConfig]): Seq[ModuleConfig] =
    self.flatMap {
      case self: CoursierModuleConfig => that.collectFirst {
          case that: CoursierModuleConfig => CoursierModuleConfig(
              self.repositories.intersect(that.repositories)
            )
        }
      case self: JavaHomeModuleConfig => that.collectFirst {
          case that: JavaHomeModuleConfig => JavaHomeModuleConfig(
              abstractedValue(self.jvmId, that.jvmId)
            )
        }
      case self: RunModuleConfig => that.collectFirst {
          case that: RunModuleConfig => RunModuleConfig(
              abstractedValue(self.forkWorkingDir, that.forkWorkingDir)
            )
        }
      case self: JavaModuleConfig => that.collectFirst {
          case that: JavaModuleConfig => JavaModuleConfig(
              self.mvnDeps.intersect(that.mvnDeps),
              self.compileMvnDeps.intersect(that.compileMvnDeps),
              self.runMvnDeps.intersect(that.runMvnDeps),
              self.bomMvnDeps.intersect(that.bomMvnDeps),
              self.moduleDeps.intersect(that.moduleDeps),
              self.compileModuleDeps.intersect(that.compileModuleDeps),
              self.runModuleDeps.intersect(that.runModuleDeps),
              abstractedOptions(self.javacOptions, that.javacOptions),
              abstractedValue(self.artifactName, that.artifactName)
            )
        }
      case self: PublishModuleConfig => that.collectFirst {
          case that: PublishModuleConfig => PublishModuleConfig(
              abstractedValue(self.pomPackagingType, that.pomPackagingType),
              abstractedValue(self.pomParentProject, that.pomParentProject, None),
              abstractedValue(self.pomSettings, that.pomSettings),
              abstractedValue(self.publishVersion, that.publishVersion),
              abstractedValue(self.versionScheme, that.versionScheme, None),
              self.publishProperties.toSeq.intersect(that.publishProperties.toSeq).toMap
            )
        }
      case self: ErrorProneModuleConfig => that.collectFirst {
          case that: ErrorProneModuleConfig => ErrorProneModuleConfig(
              abstractedValue(self.errorProneVersion, that.errorProneVersion),
              self.errorProneOptions.intersect(that.errorProneOptions),
              self.errorProneJavacEnableOptions.intersect(that.errorProneJavacEnableOptions),
              self.errorProneDeps.intersect(that.errorProneDeps)
            )
        }
      case self: ScalaModuleConfig => that.collectFirst {
          case that: ScalaModuleConfig => ScalaModuleConfig(
              abstractedValue(self.scalaVersion, that.scalaVersion),
              abstractedOptions(self.scalacOptions, that.scalacOptions),
              self.scalacPluginMvnDeps.intersect(that.scalacPluginMvnDeps)
            )
        }
      case self: ScalaJSModuleConfig => that.collectFirst {
          case that: ScalaJSModuleConfig => ScalaJSModuleConfig(
              abstractedValue(self.scalaJSVersion, that.scalaJSVersion)
            )
        }
      case self: ScalaNativeModuleConfig => that.collectFirst {
          case that: ScalaNativeModuleConfig => ScalaNativeModuleConfig(
              abstractedValue(self.scalaNativeVersion, that.scalaNativeVersion)
            )
        }
      case self: SbtPlatformModuleConfig => that.collectFirst {
          case that: SbtPlatformModuleConfig => SbtPlatformModuleConfig(
              self.sourcesRootFolders.intersect(that.sourcesRootFolders)
            )
        }
    }

  /**
   * Computes the overriding configurations for `self` when extending `base`.
   */
  def inherited(self: Seq[ModuleConfig], base: Seq[ModuleConfig]): Seq[ModuleConfig] =
    self.map {
      case self: CoursierModuleConfig => base.collectFirst {
          case base: CoursierModuleConfig => CoursierModuleConfig(
              self.repositories.diff(base.repositories)
            )
        }.getOrElse(self)
      case self: JavaHomeModuleConfig => base.collectFirst {
          case base: JavaHomeModuleConfig => JavaHomeModuleConfig(
              inheritedValue(self.jvmId, base.jvmId)
            )
        }.getOrElse(self)
      case self: RunModuleConfig => base.collectFirst {
          case base: RunModuleConfig => RunModuleConfig(
              inheritedValue(self.forkWorkingDir, base.forkWorkingDir)
            )
        }.getOrElse(self)
      case self: JavaModuleConfig => base.collectFirst {
          case base: JavaModuleConfig => JavaModuleConfig(
              self.mvnDeps.diff(base.mvnDeps),
              self.compileMvnDeps.diff(base.compileMvnDeps),
              self.runMvnDeps.diff(base.runMvnDeps),
              self.bomMvnDeps.diff(base.bomMvnDeps),
              self.moduleDeps.diff(base.moduleDeps),
              self.compileModuleDeps.diff(base.compileModuleDeps),
              self.runModuleDeps.diff(base.runModuleDeps),
              inheritedOptions(self.javacOptions, base.javacOptions),
              inheritedValue(self.artifactName, base.artifactName)
            )
        }.getOrElse(self)
      case self: PublishModuleConfig => base.collectFirst {
          case base: PublishModuleConfig => PublishModuleConfig(
              inheritedValue(self.pomPackagingType, base.pomPackagingType),
              inheritedValue(self.pomParentProject, base.pomParentProject, None),
              inheritedValue(self.pomSettings, base.pomSettings),
              inheritedValue(self.publishVersion, base.publishVersion),
              inheritedValue(self.versionScheme, base.versionScheme, None),
              self.publishProperties.toSeq.diff(base.publishProperties.toSeq).toMap
            )
        }.getOrElse(self)
      case self: ErrorProneModuleConfig => base.collectFirst {
          case base: ErrorProneModuleConfig => ErrorProneModuleConfig(
              inheritedValue(self.errorProneVersion, base.errorProneVersion),
              self.errorProneOptions.diff(base.errorProneOptions),
              self.errorProneJavacEnableOptions.diff(base.errorProneJavacEnableOptions),
              self.errorProneDeps.diff(base.errorProneDeps)
            )
        }.getOrElse(self)
      case self: ScalaModuleConfig => base.collectFirst {
          case base: ScalaModuleConfig => ScalaModuleConfig(
              inheritedValue(self.scalaVersion, base.scalaVersion),
              inheritedOptions(self.scalacOptions, base.scalacOptions),
              self.scalacPluginMvnDeps.diff(base.scalacPluginMvnDeps)
            )
        }.getOrElse(self)
      case self: ScalaJSModuleConfig => base.collectFirst {
          case base: ScalaJSModuleConfig => ScalaJSModuleConfig(
              inheritedValue(self.scalaJSVersion, base.scalaJSVersion)
            )
        }.getOrElse(self)
      case self: ScalaNativeModuleConfig => base.collectFirst {
          case base: ScalaNativeModuleConfig => ScalaNativeModuleConfig(
              inheritedValue(self.scalaNativeVersion, base.scalaNativeVersion)
            )
        }.getOrElse(self)
      case self: SbtPlatformModuleConfig => base.collectFirst {
          case base: SbtPlatformModuleConfig => SbtPlatformModuleConfig(
              self.sourcesRootFolders.diff(base.sourcesRootFolders)
            )
        }.getOrElse(self)
    }

  def abstractedOptions(self: Seq[String], base: Seq[String]): Seq[String] = {
    groupedOptions(self).intersect(groupedOptions(base)).flatten
  }

  def groupedOptions(options: Seq[String]): Seq[Seq[String]] = {
    val b = Seq.newBuilder[Seq[String]]
    var rem = options
    while (rem.nonEmpty) {
      val option = rem.head +: rem.tail.takeWhile(!_.startsWith("-"))
      b += option
      rem = rem.drop(option.length)
    }
    b.result()
  }

  def inheritedOptions(self: Seq[String], base: Seq[String]): Seq[String] = {
    groupedOptions(self).diff(groupedOptions(base)).flatten
  }

  def abstractedValue[A](self: A, that: A, default: A = null): A =
    if (self == that) self else default

  def inheritedValue[A](self: A, base: A, default: A = null): A =
    if (self == base) default else self

  case class Artifact(group: String, id: String, version: String)
  object Artifact {
    implicit val rw: ReadWriter[Artifact] = macroRW
  }

  sealed trait CrossVersion {
    def platformed: Boolean
  }
  object CrossVersion {
    case class Constant(value: String, platformed: Boolean) extends CrossVersion
    object Constant {
      implicit val rw: ReadWriter[Constant] = macroRW
    }
    case class Binary(platformed: Boolean) extends CrossVersion
    object Binary {
      implicit val rw: ReadWriter[Binary] = macroRW
    }
    case class Full(platformed: Boolean) extends CrossVersion
    object Full {
      implicit val rw: ReadWriter[Full] = macroRW
    }
    implicit val rw: ReadWriter[CrossVersion] = macroRW
  }

  case class Developer(
      id: String = null,
      name: String = null,
      url: String = null,
      organization: Option[String] = None,
      organizationUrl: Option[String] = None
  )
  object Developer {
    implicit val rw: ReadWriter[Developer] = macroRW
  }

  case class License(
      id: String = null,
      name: String = null,
      url: String = "",
      isOsiApproved: Boolean = false,
      isFsfLibre: Boolean = false,
      distribution: String = ""
  )
  object License {
    implicit val rw: ReadWriter[License] = macroRW
  }

  /**
   * Represents a module dependency.
   *
   * @param segments  Path identifying the module.
   * @param crossArgs Cross version arguments for a value in `segments` identified by its index.
   *                  The key `-1` can be used to specify the argument for the build root module.
   */
  case class ModuleDep(segments: Seq[String], crossArgs: Map[Int, String] = Map())
  object ModuleDep {
    implicit val rw: ReadWriter[ModuleDep] = macroRW
  }

  case class MvnDep(
      organization: String,
      name: String,
      version: Option[String] = None,
      classifier: Option[String] = None,
      `type`: Option[String] = None,
      excludes: Seq[(String, String)] = Nil,
      cross: CrossVersion = CrossVersion.Constant("", false)
  )
  object MvnDep {
    implicit val rw: ReadWriter[MvnDep] = macroRW
  }

  case class PomSettings(
      description: String = null,
      // used to set artifactMetadata.group
      organization: String = null,
      url: String = null,
      licenses: Seq[License] = Nil,
      versionControl: VersionControl = VersionControl(),
      developers: Seq[Developer] = Nil
  )
  object PomSettings {
    implicit val rw: ReadWriter[PomSettings] = macroRW
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

  implicit val rw: ReadWriter[ModuleConfig] = macroRW
}

case class CoursierModuleConfig(repositories: Seq[String] = Nil) extends ModuleConfig
object CoursierModuleConfig {
  implicit val rw: ReadWriter[CoursierModuleConfig] = macroRW
}

case class JavaHomeModuleConfig(jvmId: String) extends ModuleConfig
object JavaHomeModuleConfig {
  implicit val rw: ReadWriter[JavaHomeModuleConfig] = macroRW
}

case class RunModuleConfig(forkWorkingDir: String = null) extends ModuleConfig
object RunModuleConfig {
  implicit val rw: ReadWriter[RunModuleConfig] = macroRW
}

case class JavaModuleConfig(
    mvnDeps: Seq[ModuleConfig.MvnDep] = Nil,
    compileMvnDeps: Seq[ModuleConfig.MvnDep] = Nil,
    runMvnDeps: Seq[ModuleConfig.MvnDep] = Nil,
    bomMvnDeps: Seq[ModuleConfig.MvnDep] = Nil,
    moduleDeps: Seq[ModuleConfig.ModuleDep] = Nil,
    compileModuleDeps: Seq[ModuleConfig.ModuleDep] = Nil,
    runModuleDeps: Seq[ModuleConfig.ModuleDep] = Nil,
    javacOptions: Seq[String] = Nil,
    artifactName: String = null
) extends ModuleConfig
object JavaModuleConfig {
  implicit val rw: ReadWriter[JavaModuleConfig] = macroRW
}

case class PublishModuleConfig(
    pomPackagingType: String = null,
    pomParentProject: Option[ModuleConfig.Artifact] = None,
    pomSettings: ModuleConfig.PomSettings = null,
    publishVersion: String = null,
    versionScheme: Option[String] = None,
    publishProperties: Map[String, String] = Map()
) extends ModuleConfig
object PublishModuleConfig {
  implicit val rw: ReadWriter[PublishModuleConfig] = macroRW
}

case class ErrorProneModuleConfig(
    errorProneVersion: String = null,
    errorProneOptions: Seq[String] = Nil,
    errorProneJavacEnableOptions: Seq[String] = Nil,
    errorProneDeps: Seq[ModuleConfig.MvnDep] = Nil
) extends ModuleConfig
object ErrorProneModuleConfig {
  implicit val rw: ReadWriter[ErrorProneModuleConfig] = macroRW
}

case class ScalaModuleConfig(
    scalaVersion: String = null,
    scalacOptions: Seq[String] = Nil,
    scalacPluginMvnDeps: Seq[ModuleConfig.MvnDep] = Nil
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

case class SbtPlatformModuleConfig(sourcesRootFolders: Seq[String] = Nil) extends ModuleConfig
object SbtPlatformModuleConfig {
  implicit val rw: ReadWriter[SbtPlatformModuleConfig] = macroRW
}
