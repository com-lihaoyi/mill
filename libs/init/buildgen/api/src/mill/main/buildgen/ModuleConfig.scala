package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

import scala.util.matching.Regex

/**
 * ADT that defines configuration settings for a build module.
 */
sealed trait ModuleConfig
object ModuleConfig {
  implicit val rw: ReadWriter[ModuleConfig] = macroRW

  /**
   * An operation that computes the base configuration for the given inputs. Settings that cannot
   * be shared are set to an empty (`null` for single valued setting) value.
   */
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
      case self: JavaHomeModuleConfig => that.collectFirst {
          case that: JavaHomeModuleConfig => JavaHomeModuleConfig(
              value(self.jvmId, that.jvmId)
            )
        }
      case self: RunModuleConfig => that.collectFirst {
          case that: RunModuleConfig => RunModuleConfig(
              value(self.forkWorkingDir, that.forkWorkingDir)
            )
        }
      case self: JavaModuleConfig => that.collectFirst {
          case that: JavaModuleConfig => JavaModuleConfig(
              self.mandatoryMvnDeps.intersect(that.mandatoryMvnDeps),
              self.mvnDeps.intersect(that.mvnDeps),
              self.compileMvnDeps.intersect(that.compileMvnDeps),
              self.runMvnDeps.intersect(that.runMvnDeps),
              self.bomMvnDeps.intersect(that.bomMvnDeps),
              self.moduleDeps.intersect(that.moduleDeps),
              self.compileModuleDeps.intersect(that.compileModuleDeps),
              self.runModuleDeps.intersect(that.runModuleDeps),
              args(self.javacOptions, that.javacOptions)
            )
        }
      case self: PublishModuleConfig => that.collectFirst {
          case that: PublishModuleConfig => PublishModuleConfig(
              value(self.pomPackagingType, that.pomPackagingType),
              value(self.pomParentProject, that.pomParentProject, None),
              value(self.pomSettings, that.pomSettings),
              value(self.publishVersion, that.publishVersion),
              value(self.versionScheme, that.versionScheme, None),
              value(self.artifactMetadata, that.artifactMetadata),
              self.publishProperties.toSeq.intersect(that.publishProperties.toSeq).toMap
            )
        }
      case self: ErrorProneModuleConfig => that.collectFirst {
          case that: ErrorProneModuleConfig => ErrorProneModuleConfig(
              self.errorProneOptions.intersect(that.errorProneOptions),
              self.errorProneJavacEnableOptions.intersect(that.errorProneJavacEnableOptions),
              self.errorProneDeps.intersect(that.errorProneDeps)
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

  /**
   * An operation that computes the overriding configuration when extending a
   * [[abstracted base configuration]]. Settings completely defined in `base` are set to an empty
   * (`null` for single valued setting) value.
   */
  def inherited(self: Seq[ModuleConfig], base: Seq[ModuleConfig]) = {
    def args(self: Seq[String], base: Seq[String]) = self.indexOfSlice(base) match {
      case -1 => self
      case i => self.take(i) ++ self.drop(i + base.length)
    }
    def value[A](self: A, base: A, default: A = null): A = if (self == base) default else self

    self.map {
      case self: CoursierModuleConfig => base.collectFirst {
          case base: CoursierModuleConfig => CoursierModuleConfig(
              self.repositories.diff(base.repositories)
            )
        }.getOrElse(self)
      case self: JavaHomeModuleConfig => base.collectFirst {
          case base: JavaHomeModuleConfig => JavaHomeModuleConfig(
              value(self.jvmId, base.jvmId)
            )
        }.getOrElse(self)
      case self: RunModuleConfig => base.collectFirst {
          case base: RunModuleConfig => RunModuleConfig(
              value(self.forkWorkingDir, base.forkWorkingDir)
            )
        }.getOrElse(self)
      case self: JavaModuleConfig => base.collectFirst {
          case base: JavaModuleConfig => JavaModuleConfig(
              self.mandatoryMvnDeps.diff(base.mandatoryMvnDeps),
              self.mvnDeps.diff(base.mvnDeps),
              self.compileMvnDeps.diff(base.compileMvnDeps),
              self.runMvnDeps.diff(base.runMvnDeps),
              self.bomMvnDeps.diff(base.bomMvnDeps),
              self.moduleDeps.diff(base.moduleDeps),
              self.compileModuleDeps.diff(base.compileModuleDeps),
              self.runModuleDeps.diff(base.runModuleDeps),
              args(self.javacOptions, base.javacOptions)
            )
        }.getOrElse(self)
      case self: PublishModuleConfig => base.collectFirst {
          case base: PublishModuleConfig => PublishModuleConfig(
              value(self.pomPackagingType, base.pomPackagingType),
              value(self.pomParentProject, base.pomParentProject, None),
              value(self.pomSettings, base.pomSettings),
              value(self.publishVersion, base.publishVersion),
              value(self.versionScheme, base.versionScheme, None),
              value(self.artifactMetadata, base.artifactMetadata),
              self.publishProperties.toSeq.diff(base.publishProperties.toSeq).toMap
            )
        }.getOrElse(self)
      case self: ErrorProneModuleConfig => base.collectFirst {
          case base: ErrorProneModuleConfig => ErrorProneModuleConfig(
              self.errorProneOptions.diff(base.errorProneOptions),
              self.errorProneJavacEnableOptions.diff(base.errorProneJavacEnableOptions),
              self.errorProneDeps.diff(base.errorProneDeps)
            )
        }.getOrElse(self)
      case self: ScalaModuleConfig => base.collectFirst {
          case base: ScalaModuleConfig => ScalaModuleConfig(
              value(self.scalaVersion, base.scalaVersion),
              args(self.scalacOptions, base.scalacOptions),
              self.scalacPluginMvnDeps.diff(base.scalacPluginMvnDeps)
            )
        }.getOrElse(self)
      case self: ScalaJSModuleConfig => base.collectFirst {
          case base: ScalaJSModuleConfig => ScalaJSModuleConfig(
              value(self.scalaJSVersion, base.scalaJSVersion)
            )
        }.getOrElse(self)
      case self: ScalaNativeModuleConfig => base.collectFirst {
          case base: ScalaNativeModuleConfig => ScalaNativeModuleConfig(
              value(self.scalaNativeVersion, base.scalaNativeVersion)
            )
        }.getOrElse(self)
    }
  }
}

case class CoursierModuleConfig(repositories: Seq[String] = Nil) extends ModuleConfig
object CoursierModuleConfig {
  implicit val rw: ReadWriter[CoursierModuleConfig] = macroRW
}

case class JavaHomeModuleConfig(jvmId: String) extends ModuleConfig
object JavaHomeModuleConfig {

  def jvmId(version: Int) = Option(version match {
    case i if i < 11 => null // https://github.com/com-lihaoyi/mill/issues/5782
    case 8 | 11 | 17 | 21 => version.toString // default JDK, temurin, supports LTS versions
    case _ => s"zulu:$version"
  })

  implicit val rw: ReadWriter[JavaHomeModuleConfig] = macroRW
}

case class RunModuleConfig(forkWorkingDir: String = null) extends ModuleConfig
object RunModuleConfig {
  implicit val rw: ReadWriter[RunModuleConfig] = macroRW
}

case class JavaModuleConfig(
    mandatoryMvnDeps: Seq[String] = Nil,
    mvnDeps: Seq[String] = Nil,
    compileMvnDeps: Seq[String] = Nil,
    runMvnDeps: Seq[String] = Nil,
    bomMvnDeps: Seq[String] = Nil,
    moduleDeps: Seq[JavaModuleConfig.ModuleDep] = Nil,
    compileModuleDeps: Seq[JavaModuleConfig.ModuleDep] = Nil,
    runModuleDeps: Seq[JavaModuleConfig.ModuleDep] = Nil,
    javacOptions: Seq[String] = Nil
) extends ModuleConfig
object JavaModuleConfig {

  val mvnDepOrgNameRegex: Regex = """^mvn"([^:]+)[:]+([^:"]+)[:"].*$""".r

  def isBomMvnDep(mvnDep: String): Boolean = {
    val mvnDepOrgNameRegex(org, name) = mvnDep: @unchecked
    name.endsWith("-bom") ||
    (org == "org.springframework.boot" && name == "spring-boot-dependencies")
  }

  def mvnDep(
      org: String,
      name: String,
      version: String = null,
      classifier: Option[String] = None,
      typ: Option[String] = None,
      excludes: Iterable[(String, String)] = Nil,
      sep1: String = ":",
      sep2: String = ":"
  ): String = {
    var suffix =
      (version match {
        case null => ""
        case _ => version
      }) + classifier.fold("") {
        case null | "" => ""
        case attr => s";classifier=$attr"
      } + typ.fold("") {
        case null | "" | "jar" => ""
        case attr => s";type=$typ"
      } + excludes.iterator.map {
        case (org, name) => s";exclude=$org:$name"
      }.mkString
    if (suffix.nonEmpty) suffix = sep2 + suffix
    s"""mvn"$org$sep1$name$suffix""""
  }

  def unsupportedJavacOptions = Seq(
    // TODO Supporting -Werror would require removing non-existent paths from the classpath
    "-Werror"
  )

  /**
   * Represents a module dependency.
   * @param segments Path identifying the module.
   * @param crossArgs Cross version arguments for a value in `segments` identified by its index.
   *                  The key `-1` can be used to specify the argument for the build root module.
   */
  case class ModuleDep(segments: Seq[String], crossArgs: Map[Int, String] = Map())
  object ModuleDep {
    implicit val rw: ReadWriter[ModuleDep] = macroRW
  }
  implicit val rw: ReadWriter[JavaModuleConfig] = macroRW
}

case class PublishModuleConfig(
    pomPackagingType: String = null,
    pomParentProject: Option[PublishModuleConfig.Artifact] = None,
    pomSettings: PublishModuleConfig.PomSettings = null,
    publishVersion: String = null,
    versionScheme: Option[String] = None,
    artifactMetadata: PublishModuleConfig.Artifact = null,
    publishProperties: Map[String, String] = Map()
) extends ModuleConfig
object PublishModuleConfig {
  case class Artifact(group: String, id: String, version: String)
  object Artifact {
    implicit val rw: ReadWriter[Artifact] = macroRW
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
      id: String = null,
      name: String = null,
      url: String = null,
      organization: Option[String] = None,
      organizationUrl: Option[String] = None
  )
  object Developer {
    implicit val rw: ReadWriter[Developer] = macroRW
  }
  case class PomSettings(
      description: String = null,
      organization: String = null,
      url: String = null,
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

case class ErrorProneModuleConfig(
    errorProneOptions: Seq[String] = Nil,
    errorProneJavacEnableOptions: Seq[String] = Nil,
    errorProneDeps: Seq[String] = Nil
) extends ModuleConfig
object ErrorProneModuleConfig {
  implicit val rw: ReadWriter[ErrorProneModuleConfig] = macroRW

  def javacOptionsAndConfig(javacOptions: Seq[String], errorProneDeps: Seq[String] = Nil) = {
    val (pluginOptions, javacOptions0) =
      javacOptions.partition(s => s.startsWith("-Xplugin:ErrorProne") || s.startsWith("-XD"))
    (
      javacOptions0,
      pluginOptions.collectFirst {
        case s if s.startsWith("-Xplugin:ErrorProne") =>
          ErrorProneModuleConfig(
            errorProneOptions = s.split(" ").toSeq.tail,
            errorProneJavacEnableOptions = pluginOptions.diff(Seq(s)),
            errorProneDeps = errorProneDeps
          )
      }
    )
  }
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
