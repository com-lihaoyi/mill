package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

/**
 * Data for configuring a Mill build module.
 *
 * Each subtype in this ADT maps to a module type defined in Mill.
 * For example, `ModuleConfig.JavaModule` maps to `mill.javalib.JavaModule` and contains the data
 * to configure tasks/members like `mvnDeps`/`moduleDeps`.
 */
sealed trait ModuleConfig
object ModuleConfig {
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
      url: String = null,
      isOsiApproved: Boolean = false,
      isFsfLibre: Boolean = false,
      distribution: String = ""
  )
  object License {
    implicit val rw: ReadWriter[License] = macroRW
  }
  case class ModuleDep(
      segments: Seq[String],
      // parameters by segment index, with `-1` reserved for the root module
      crossArgs: Map[Int, Seq[String]] = Map()
  )
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
      cross: CrossVersion = CrossVersion.Constant("", platformed = false)
  ) {
    def module = (organization, name)
    override def toString = {
      super.toString
      val sep = cross match {
        case _: CrossVersion.Full => ":::"
        case _: CrossVersion.Binary => "::"
        case _ => ":"
      }
      val nameSuffix = cross match {
        case v: CrossVersion.Constant => v.value
        case _ => ""
      }
      var suffix = version.getOrElse("") + classifier.fold("") {
        case "" => ""
        case attr => s";classifier=$attr"
      } + `type`.fold("") {
        case "" | "jar" => ""
        case attr => s";type=$attr"
      } + excludes.map {
        case (org, name) => s";exclude=$org:$name"
      }.mkString
      if (suffix.nonEmpty) {
        val sep = if (cross.platformed) "::" else ":"
        suffix = sep + suffix
      }
      s"""mvn"$organization$sep$name$nameSuffix$suffix""""
    }
  }
  object MvnDep {
    implicit val rw: ReadWriter[MvnDep] = macroRW
  }
  case class PomSettings(
      description: Option[String] = None,
      organization: Option[String] = None, // used to set artifactMetadata.group
      url: Option[String] = None,
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

  case class CoursierModule(repositories: Seq[String] = Nil) extends ModuleConfig {
    def abstracted(that: CoursierModule) = copy(
      repositories.intersect(that.repositories)
    )
    def inherited(base: CoursierModule) = copy(
      repositories.diff(base.repositories)
    )
  }
  object CoursierModule {
    implicit val rw: ReadWriter[CoursierModule] = macroRW
  }
  case class JavaHomeModule(jvmId: String) extends ModuleConfig {
    def abstracted(that: JavaHomeModule) = copy(
      abstractedValue(jvmId, that.jvmId)
    )
    def inherited(base: JavaHomeModule) = copy(
      inheritedValue(jvmId, base.jvmId)
    )
  }
  object JavaHomeModule {
    implicit val rw: ReadWriter[JavaHomeModule] = macroRW

    def find(
        javaVersion: Option[Int] = None,
        javacOptions: Seq[String] = Nil,
        scalacOptions: Seq[String] = Nil
    ) = {
      def versionIn(options: Seq[String], regex: String) =
        options.indexWhere(_.matches(regex)) match {
          case -1 => None
          case i => options.lift(i + 1).map(_.stripPrefix("1.").toInt)
        }
      javaVersion
        .orElse(versionIn(javacOptions, "--release|--?target"))
        .orElse(versionIn(scalacOptions, "--?release"))
        .map { version =>
          // Mill requires Java 11+
          val version0 = 11.max(version)
          // use any distribution that supports all Java versions
          JavaHomeModule(jvmId = s"zulu:$version0")
        }
    }
  }
  case class RunModule(forkWorkingDir: String = null) extends ModuleConfig {
    def abstracted(that: RunModule) = copy(
      abstractedValue(forkWorkingDir, that.forkWorkingDir)
    )
    def inherited(base: RunModule) = copy(
      inheritedValue(forkWorkingDir, base.forkWorkingDir)
    )
  }
  object RunModule {
    implicit val rw: ReadWriter[RunModule] = macroRW
  }
  case class JavaModule(
      mvnDeps: Seq[MvnDep] = Nil,
      compileMvnDeps: Seq[MvnDep] = Nil,
      runMvnDeps: Seq[MvnDep] = Nil,
      bomMvnDeps: Seq[MvnDep] = Nil,
      moduleDeps: Seq[ModuleDep] = Nil,
      compileModuleDeps: Seq[ModuleDep] = Nil,
      runModuleDeps: Seq[ModuleDep] = Nil,
      javacOptions: Seq[String] = Nil,
      artifactName: String = null
  ) extends ModuleConfig {
    def abstracted(that: JavaModule) = copy(
      mvnDeps.intersect(that.mvnDeps),
      compileMvnDeps.intersect(that.compileMvnDeps),
      runMvnDeps.intersect(that.runMvnDeps),
      bomMvnDeps.intersect(that.bomMvnDeps),
      moduleDeps.intersect(that.moduleDeps),
      compileModuleDeps.intersect(that.compileModuleDeps),
      runModuleDeps.intersect(that.runModuleDeps),
      abstractedOptions(javacOptions, that.javacOptions),
      abstractedValue(artifactName, that.artifactName)
    )
    def inherited(base: JavaModule) = copy(
      mvnDeps.diff(base.mvnDeps),
      compileMvnDeps.diff(base.compileMvnDeps),
      runMvnDeps.diff(base.runMvnDeps),
      bomMvnDeps.diff(base.bomMvnDeps),
      moduleDeps.diff(base.moduleDeps),
      compileModuleDeps.diff(base.compileModuleDeps),
      runModuleDeps.diff(base.runModuleDeps),
      inheritedOptions(javacOptions, base.javacOptions),
      inheritedValue(artifactName, base.artifactName)
    )
  }
  object JavaModule {
    implicit val rw: ReadWriter[JavaModule] = macroRW
  }
  case class PublishModule(
      pomPackagingType: String = null,
      pomParentProject: Artifact = null,
      pomSettings: PomSettings = null,
      publishVersion: String = null,
      versionScheme: String = null,
      publishProperties: Map[String, String] = Map()
  ) extends ModuleConfig {
    def abstracted(that: PublishModule) = copy(
      abstractedValue(pomPackagingType, that.pomPackagingType),
      abstractedValue(pomParentProject, that.pomParentProject),
      abstractedValue(pomSettings, that.pomSettings),
      abstractedValue(publishVersion, that.publishVersion),
      abstractedValue(versionScheme, that.versionScheme),
      publishProperties.toSeq.intersect(that.publishProperties.toSeq).toMap
    )
    def inherited(base: PublishModule) = copy(
      inheritedValue(pomPackagingType, base.pomPackagingType),
      inheritedValue(pomParentProject, base.pomParentProject),
      inheritedValue(pomSettings, base.pomSettings),
      inheritedValue(publishVersion, base.publishVersion),
      inheritedValue(versionScheme, base.versionScheme),
      publishProperties.toSeq.diff(base.publishProperties.toSeq).toMap
    )
  }
  object PublishModule {
    implicit val rw: ReadWriter[PublishModule] = macroRW

    def pomPackagingTypeOverride(packaging: String): String = packaging match {
      case "" | "jar" => null
      case _ => packaging
    }
  }
  case class ErrorProneModule(
      errorProneVersion: String = null,
      errorProneDeps: Seq[MvnDep] = Nil,
      errorProneOptions: Seq[String] = Nil,
      errorProneJavacEnableOptions: Seq[String] = Nil
  ) extends ModuleConfig {
    def abstracted(that: ErrorProneModule) = copy(
      abstractedValue(errorProneVersion, that.errorProneVersion),
      errorProneDeps.intersect(that.errorProneDeps),
      errorProneOptions.intersect(that.errorProneOptions),
      errorProneJavacEnableOptions.intersect(that.errorProneJavacEnableOptions)
    )
    def inherited(base: ErrorProneModule) = copy(
      inheritedValue(errorProneVersion, base.errorProneVersion),
      errorProneDeps.diff(base.errorProneDeps),
      errorProneOptions.diff(base.errorProneOptions),
      errorProneJavacEnableOptions.diff(base.errorProneJavacEnableOptions)
    )
  }
  object ErrorProneModule {
    implicit val rw: ReadWriter[ErrorProneModule] = macroRW

    def find(javacOptions: Seq[String], errorProneMvnDeps: Seq[MvnDep]) = {
      javacOptions.find(_.startsWith("-Xplugin:ErrorProne")).map { epOption =>
        val epOptions = epOption.split("\\s").toSeq.tail
        val epMvnDep =
          errorProneMvnDeps.find(_.module == ("com.google.errorprone", "error_prone_core"))
        val (epJavacOptions, javacOptions0) = javacOptions
          // skip any options added by ErrorProneModule
          .filter(s => s != epOption && s != "-XDcompilePolicy=simple")
          .partition(_.startsWith("-XD"))
        val errorProneModule = Some(apply(
          errorProneVersion = epMvnDep.flatMap(_.version).orNull,
          errorProneDeps = errorProneMvnDeps.diff(epMvnDep.toSeq),
          errorProneOptions = epOptions,
          errorProneJavacEnableOptions = epJavacOptions
        ))
        (errorProneModule, javacOptions0)
      }.getOrElse((None, javacOptions))
    }
  }
  case class ScalaModule(
      scalaVersion: String = null,
      scalacOptions: Seq[String] = Nil,
      scalacPluginMvnDeps: Seq[MvnDep] = Nil
  ) extends ModuleConfig {
    def abstracted(that: ScalaModule) = copy(
      abstractedValue(scalaVersion, that.scalaVersion),
      abstractedOptions(scalacOptions, that.scalacOptions),
      scalacPluginMvnDeps.intersect(that.scalacPluginMvnDeps)
    )
    def inherited(base: ScalaModule) = copy(
      inheritedValue(scalaVersion, base.scalaVersion),
      inheritedOptions(scalacOptions, base.scalacOptions),
      scalacPluginMvnDeps.diff(base.scalacPluginMvnDeps)
    )
  }
  object ScalaModule {
    implicit val rw: ReadWriter[ScalaModule] = macroRW
  }
  case class ScalaJSModule(
      scalaJSVersion: String = null,
      moduleKind: String = null
  ) extends ModuleConfig {
    def abstracted(that: ScalaJSModule) = copy(
      abstractedValue(scalaJSVersion, that.scalaJSVersion),
      abstractedValue(moduleKind, that.moduleKind)
    )
    def inherited(base: ScalaJSModule) = copy(
      inheritedValue(scalaJSVersion, base.scalaJSVersion),
      inheritedValue(moduleKind, base.moduleKind)
    )
  }
  object ScalaJSModule {
    implicit val rw: ReadWriter[ScalaJSModule] = macroRW

    def moduleKindOverride(value: String) = value match {
      case "" | "NoModule" => null
      case _ => value
    }
  }
  case class ScalaNativeModule(scalaNativeVersion: String = null) extends ModuleConfig {
    def abstracted(that: ScalaNativeModule) = copy(
      abstractedValue(that.scalaNativeVersion, that.scalaNativeVersion)
    )
    def inherited(base: ScalaNativeModule) = copy(
      inheritedValue(scalaNativeVersion, base.scalaNativeVersion)
    )
  }
  object ScalaNativeModule {
    implicit val rw: ReadWriter[ScalaNativeModule] = macroRW
  }
  case class SbtPlatformModule(sourcesRootFolders: Seq[String] = Nil) extends ModuleConfig {
    def abstracted(that: SbtPlatformModule) = copy(
      sourcesRootFolders.intersect(that.sourcesRootFolders)
    )
    def inherited(base: SbtPlatformModule) = copy(
      sourcesRootFolders.diff(base.sourcesRootFolders)
    )
  }
  object SbtPlatformModule {
    implicit val rw: ReadWriter[SbtPlatformModule] = macroRW
  }
  case class TestModule(
      testParallelism: String = null,
      testSandboxWorkingDir: String = null
  ) extends ModuleConfig {
    def abstracted(that: TestModule) = copy(
      abstractedValue(testParallelism, that.testParallelism),
      abstractedValue(testSandboxWorkingDir, that.testSandboxWorkingDir)
    )
    def inherited(base: TestModule) = copy(
      inheritedValue(testParallelism, base.testParallelism),
      inheritedValue(testSandboxWorkingDir, base.testSandboxWorkingDir)
    )
  }
  object TestModule {
    implicit val rw: ReadWriter[TestModule] = macroRW

    def mixin(mvnDeps: Seq[MvnDep]): Option[String] = {
      val mvnModules = mvnDeps.map(_.module)
      // prioritize mixins that integrate with other frameworks
      mvnModules.collectFirst {
        case ("org.scalatest" | "org.scalatestplus", _) => "TestModule.ScalaTest"
        case ("org.specs2", _) => "TestModule.Spec2"
        case ("org.scalameta", "munit") => "TestModule.Munit"
        // https://scalameta.org/munit/docs/integrations/external-integrations.html
        case ("org.typelevel", "discipline-munit") => "TestModule.Munit"
        case ("com.alejandrohdezma", "http4s-munit") => "TestModule.Munit"
        case ("org.typelevel", name) if name.startsWith("munit-cats-effect") => "TestModule.Munit"
        case ("org.scalameta", "munit-scalacheck") => "TestModule.Munit"
        case ("com.github.lolgab", "munit-snapshot") => "TestModule.Munit"
        case ("com.github.poslegm", "munit-zio") => "TestModule.Munit"
        case ("io.github.jbwheatley", name) if name.startsWith("pact4s-") => "TestModule.Munit"
        case ("com.alejandrohdezma", "sbt-scripted-munit") => "TestModule.Munit"
        case ("qa.hedgehog", "hedgehog-munit") => "TestModule.Munit"
        case ("com.alejandrohdezma", "tapir-golden-openapi-munit") => "TestModule.Munit"
      }.orElse {
        mvnModules.collectFirst {
          case ("org.testng", _) => "TestModule.TestNg"
          case ("junit", _) => "TestModule.Junit4"
          case ("org.junit.jupiter", _) => "TestModule.Junit5"
          case ("com.lihaoyi", "utest") => "TestModule.Utest"
          case ("com.disneystreaming", "weaver-scalacheck") => "TestModule.Weaver"
          case ("dev.zio", "zio-test" | "zio-test-sbt") => "TestModule.ZioTest"
          case ("org.scalacheck", _) => "TestModule.ScalaCheck"
        }
      }
    }
  }
  implicit val rw: ReadWriter[ModuleConfig] = macroRW

  def isBomDep(organization: String, name: String) = name.endsWith("-bom") ||
    (organization == "org.springframework.boot" && name == "spring-boot-dependencies")

  def groupedOptions(options: Seq[String]) = {
    val b = Seq.newBuilder[Seq[String]]
    var rem = options
    while (rem.nonEmpty) {
      val option = rem.head +: rem.tail.takeWhile(!_.startsWith("-"))
      b += option
      rem = rem.drop(option.length)
    }
    b.result()
  }

  def abstractedOptions(self: Seq[String], that: Seq[String]) = {
    groupedOptions(self).intersect(groupedOptions(that)).flatten
  }

  def inheritedOptions(self: Seq[String], base: Seq[String]) = {
    groupedOptions(self).diff(groupedOptions(base)).flatten
  }

  def abstractedValue[A](self: A, that: A, default: A = null) =
    if (self == that) self else default

  def inheritedValue[A](self: A, base: A, default: A = null) =
    if (self == base) default else self

  def abstractedConfigs(self: Seq[ModuleConfig], that: Seq[ModuleConfig]) =
    self.flatMap {
      case self: CoursierModule => that.collectFirst {
          case that: CoursierModule => self.abstracted(that)
        }
      case self: JavaHomeModule => that.collectFirst {
          case that: JavaHomeModule => self.abstracted(that)
        }
      case self: RunModule => that.collectFirst {
          case that: RunModule => self.abstracted(that)
        }
      case self: JavaModule => that.collectFirst {
          case that: JavaModule => self.abstracted(that)
        }
      case self: PublishModule => that.collectFirst {
          case that: PublishModule => self.abstracted(that)
        }
      case self: ErrorProneModule => that.collectFirst {
          case that: ErrorProneModule => self.abstracted(that)
        }
      case self: ScalaModule => that.collectFirst {
          case that: ScalaModule => self.abstracted(that)
        }
      case self: ScalaJSModule => that.collectFirst {
          case that: ScalaJSModule => self.abstracted(that)
        }
      case self: ScalaNativeModule => that.collectFirst {
          case that: ScalaNativeModule => self.abstracted(that)
        }
      case self: SbtPlatformModule => that.collectFirst {
          case that: SbtPlatformModule => self.abstracted(that)
        }
      case self: TestModule => that.collectFirst {
          case that: TestModule => self.abstracted(that)
        }
    }

  def inheritedConfigs(self: Seq[ModuleConfig], base: Seq[ModuleConfig]) =
    self.map {
      case self: CoursierModule => base.collectFirst {
          case base: CoursierModule => self.inherited(base)
        }.getOrElse(self)
      case self: JavaHomeModule => base.collectFirst {
          case base: JavaHomeModule => self.inherited(base)
        }.getOrElse(self)
      case self: RunModule => base.collectFirst {
          case base: RunModule => self.inherited(base)
        }.getOrElse(self)
      case self: JavaModule => base.collectFirst {
          case base: JavaModule => self.inherited(base)
        }.getOrElse(self)
      case self: PublishModule => base.collectFirst {
          case base: PublishModule => self.inherited(base)
        }.getOrElse(self)
      case self: ErrorProneModule => base.collectFirst {
          case base: ErrorProneModule => self.inherited(base)
        }.getOrElse(self)
      case self: ScalaModule => base.collectFirst {
          case base: ScalaModule => self.inherited(base)
        }.getOrElse(self)
      case self: ScalaJSModule => base.collectFirst {
          case base: ScalaJSModule => self.inherited(base)
        }.getOrElse(self)
      case self: ScalaNativeModule => base.collectFirst {
          case base: ScalaNativeModule => self.inherited(base)
        }.getOrElse(self)
      case self: SbtPlatformModule => base.collectFirst {
          case base: SbtPlatformModule => self.inherited(base)
        }.getOrElse(self)
      case self: TestModule => base.collectFirst {
          case base: TestModule => self.inherited(base)
        }.getOrElse(self)
    }
}
