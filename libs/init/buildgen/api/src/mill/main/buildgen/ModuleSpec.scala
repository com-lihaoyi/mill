package mill.main.buildgen

import mill.main.buildgen.ModuleSpec._
import upickle.default.{ReadWriter, macroRW, readwriter}

import scala.language.implicitConversions

case class ModuleSpec(
    name: String,
    imports: Seq[String] = Nil,
    supertypes: Seq[String] = Nil,
    crossKeys: Seq[String] = Nil,
    alias: Option[String] = None,
    moduleDir: Value[String] = Value(),
    repositories: Values[String] = Nil,
    forkArgs: Values[Opt] = Values(),
    forkWorkingDir: Value[String] = Value(),
    mandatoryMvnDeps: Values[MvnDep] = Values(),
    mvnDeps: Values[MvnDep] = Values(),
    compileMvnDeps: Values[MvnDep] = Values(),
    runMvnDeps: Values[MvnDep] = Values(),
    bomMvnDeps: Values[MvnDep] = Values(),
    depManagement: Values[MvnDep] = Values(),
    javacOptions: Values[Opt] = Values(),
    moduleDeps: Values[ModuleDep] = Values(),
    compileModuleDeps: Values[ModuleDep] = Values(),
    runModuleDeps: Values[ModuleDep] = Values(),
    bomModuleDeps: Values[ModuleDep] = Values(),
    sourcesFolders: Values[String] = Values(),
    sources: Values[os.RelPath] = Values(),
    resources: Values[os.RelPath] = Values(),
    artifactName: Value[String] = Value(),
    pomPackagingType: Value[String] = Value(),
    pomParentProject: Value[Artifact] = Value(),
    pomSettings: Value[PomSettings] = Value(),
    publishVersion: Value[String] = Value(),
    versionScheme: Value[String] = Value(),
    publishProperties: Values[(String, String)] = Values(),
    errorProneDeps: Values[MvnDep] = Values(),
    errorProneOptions: Values[String] = Values(),
    errorProneJavacEnableOptions: Values[Opt] = Values(),
    jmhCoreVersion: Value[String] = Value(),
    checkstyleProperties: Values[(String, String)] = Values(),
    checkstyleMvnDeps: Values[MvnDep] = Values(),
    checkstyleConfig: Value[os.RelPath] = Value(),
    checkstyleVersion: Value[String] = Value(),
    scalaVersion: Value[String] = Value(),
    scalacOptions: Values[Opt] = Values(),
    scalacPluginMvnDeps: Values[MvnDep] = Values(),
    scalaJSVersion: Value[String] = Value(),
    moduleKind: Value[String] = Value(),
    scalaNativeVersion: Value[String] = Value(),
    sourcesRootFolders: Values[String] = Values(),
    testParallelism: Value[Boolean] = Value(),
    testSandboxWorkingDir: Value[Boolean] = Value(),
    testFramework: Value[String] = Value(),
    scalafixConfig: Value[String] = Value(),
    scalafixIvyDeps: Values[MvnDep] = Values(),
    scoverageVersion: Value[String] = Value(),
    branchCoverageMin: Value[Double] = Value(),
    statementCoverageMin: Value[Double] = Value(),
    mimaPreviousVersions: Values[String] = Values(),
    mimaPreviousArtifacts: Values[MvnDep] = Values(),
    mimaCheckDirection: Value[String] = Value(),
    mimaBinaryIssueFilters: Values[String] = Values(),
    mimaBackwardIssueFilters: Values[(String, Seq[String])] = Values(),
    mimaForwardIssueFilters: Values[(String, Seq[String])] = Values(),
    mimaExcludeAnnotations: Values[String] = Values(),
    mimaReportSignatureProblems: Value[Boolean] = Value(),
    children: Seq[ModuleSpec] = Nil
) {

  def isBomModule: Boolean = supertypes.contains("BomModule")

  def isPublishModule: Boolean = supertypes.contains("PublishModule")

  def recMap(f: ModuleSpec => ModuleSpec): ModuleSpec =
    f(copy(children = children.map(_.recMap(f))))

  def tree: Seq[ModuleSpec] = this +: children.flatMap(_.tree)

  def withErrorProneModule(errorProneMvnDeps: Seq[MvnDep]): ModuleSpec = {
    javacOptions.base.find(_.group.head.startsWith("-Xplugin:ErrorProne")).fold(this) { epOption =>
      val epOptions = epOption.group.head.split("\\s").toSeq.tail
      val (epJavacOptions, javacOptions0) = javacOptions.base
        .diff(Seq(epOption, Opt("-XDcompilePolicy=simple")))
        .partition(_.group.head.startsWith("-XD"))
      this.copy(
        imports = "mill.javalib.errorprone.ErrorProneModule" +: imports,
        supertypes = supertypes :+ "ErrorProneModule",
        errorProneDeps = errorProneMvnDeps,
        errorProneOptions = epOptions,
        errorProneJavacEnableOptions = epJavacOptions,
        javacOptions = javacOptions0
      )
    }
  }

  def withJmhModule(jmhCoreVersion: Value[String]): ModuleSpec = copy(
    imports = "mill.contrib.jmh.JmhModule" +: imports,
    supertypes = supertypes :+ "JmhModule",
    jmhCoreVersion = jmhCoreVersion
  )

  def withCheckstyleModule(
      checkstyleProperties: Values[(String, String)] = Values(),
      checkstyleMvnDeps: Values[MvnDep] = Values(),
      checkstyleConfig: Value[os.RelPath] = Value(),
      checkstyleVersion: Value[String] = Value()
  ): ModuleSpec = copy(
    imports = "mill.javalib.checkstyle.CheckstyleModule" +: imports,
    supertypes = supertypes :+ "CheckstyleModule",
    checkstyleProperties = checkstyleProperties,
    checkstyleMvnDeps = checkstyleMvnDeps,
    checkstyleConfig = checkstyleConfig,
    checkstyleVersion = checkstyleVersion
  )

  def withJupiterInterface(junitVersion: String): ModuleSpec = {
    val Version = "(\\d+)\\.(\\d+).*".r
    junitVersion match {
      case Version("5", minor) if minor.toInt < 8 =>
        copy(mandatoryMvnDeps =
          mandatoryMvnDeps.copy(base =
            mandatoryMvnDeps.base :+ MvnDep("com.github.sbt.junit", "jupiter-interface", "0.11.4")
          )
        )
      case _ => this
    }
  }
}
object ModuleSpec {
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
  case class MvnDep(
      organization: String,
      name: String,
      version: String,
      classifier: Option[String] = None,
      `type`: Option[String] = None,
      excludes: Seq[(String, String)] = Nil,
      cross: CrossVersion = CrossVersion.Constant("", platformed = false),
      ref: Option[String] = None
  ) {
    def is(organization: String, name: String): Boolean =
      this.name == name && this.organization == organization
    override def toString: String = {
      val binarySeparator = cross match {
        case _: CrossVersion.Full => ":::"
        case _: CrossVersion.Binary => "::"
        case _ => ":"
      }
      val nameSuffix = cross match {
        case v: CrossVersion.Constant => v.value
        case _ => ""
      }
      val platformSeparator = if (version.isEmpty) "" else if (cross.platformed) "::" else ":"
      val classifierAttr = classifier.fold("") {
        case "" => ""
        case attr => s";classifier=$attr"
      }
      val typeAttr = `type`.fold("") {
        case "" | "jar" => ""
        case attr => s";type=$attr"
      }
      val excludeAttr = excludes.map { case (org, name) => s";exclude=$org:$name" }.mkString
      s"""mvn"$organization$binarySeparator$name$nameSuffix$platformSeparator$version$classifierAttr$typeAttr$excludeAttr""""
    }
  }
  object MvnDep {
    implicit val rw: ReadWriter[MvnDep] = macroRW
  }
  case class ModuleDep(
      segments: Seq[String],
      crossSuffix: Option[String] = None,
      childSegment: Option[String] = None
  )
  object ModuleDep {
    implicit val rw: ReadWriter[ModuleDep] = macroRW
  }
  case class Opt(group: Seq[String])
  object Opt {
    implicit val rw: ReadWriter[Opt] = macroRW
    def apply(head: String, tail: String*): Opt = apply(head +: tail)
    def groups(ungrouped: Seq[String]): Seq[Opt] = {
      val opts = Seq.newBuilder[Opt]
      var rem = ungrouped
      while (rem.nonEmpty) {
        val group = rem.head +: rem.tail.takeWhile(_.head != '-')
        opts += Opt(group)
        rem = rem.drop(group.length)
      }
      opts.result()
    }
  }
  case class Artifact(group: String, id: String, version: String)
  object Artifact {
    implicit val rw: ReadWriter[Artifact] = macroRW
  }
  case class License(
      id: String = "",
      name: String = "",
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
      id: String = "",
      name: String = "",
      url: String = "",
      organization: Option[String] = None,
      organizationUrl: Option[String] = None
  )
  object Developer {
    implicit val rw: ReadWriter[Developer] = macroRW
  }
  case class PomSettings(
      description: String = "",
      organization: String = "", // maps to artifactMetadata.group
      url: String = "",
      licenses: Seq[License] = Nil,
      versionControl: VersionControl = VersionControl(),
      developers: Seq[Developer] = Nil
  )
  object PomSettings {
    implicit val rw: ReadWriter[PomSettings] = macroRW
  }
  implicit val rwRelPath: ReadWriter[os.RelPath] =
    readwriter[String].bimap(_.toString, os.RelPath(_))

  case class Value[+A](base: Option[A] = None, cross: Seq[(String, A)] = Nil)
  object Value {
    implicit def rw[A: ReadWriter]: ReadWriter[Value[A]] = macroRW
    implicit def from[A](base: A): Value[A] = apply(base = Option(base))
    implicit def fromOption[A](base: Option[A]): Value[A] = apply(base = base)
  }
  case class Values[+A](
      base: Seq[A] = Nil,
      cross: Seq[(String, Seq[A])] = Nil,
      appendSuper: Boolean = false
  )
  object Values {
    implicit def rw[A: ReadWriter]: ReadWriter[Values[A]] = macroRW
    implicit def fromSeq[A](base: Seq[A]): Values[A] = apply(base = base)
  }
  implicit val rw: ReadWriter[ModuleSpec] = macroRW

  def testModuleMixin(mvnDeps: Seq[MvnDep]): Option[String] = {
    // Prioritize frameworks that integrate with other frameworks.
    mvnDeps.iterator.map(dep => dep.organization -> dep.name).collectFirst {
      case ("org.scalatest" | "org.scalatestplus", _) => "TestModule.ScalaTest"
      case ("org.specs2", _) => "TestModule.Spec2"
      // https://scalameta.org/munit/docs/integrations/external-integrations.html
      case ("org.scalameta", "munit") |
          ("org.typelevel", "discipline-munit") |
          ("com.alejandrohdezma", "http4s-munit") |
          ("org.scalameta", "munit-scalacheck") |
          ("com.github.lolgab", "munit-snapshot") |
          ("com.github.poslegm", "munit-zio") |
          ("com.alejandrohdezma", "sbt-scripted-munit") |
          ("qa.hedgehog", "hedgehog-munit") |
          ("com.alejandrohdezma", "tapir-golden-openapi-munit") => "TestModule.Munit"
      case ("org.typelevel", name) if name.startsWith("munit-cats-effect") => "TestModule.Munit"
      case ("io.github.jbwheatley", name) if name.startsWith("pact4s-") => "TestModule.Munit"
    }.orElse {
      mvnDeps.iterator.map(dep => dep.organization -> dep.name).collectFirst {
        case ("org.testng", _) => "TestModule.TestNg"
        case ("junit", _) => "TestModule.Junit4"
        case ("org.junit.jupiter", _) |
            ("org.springframework.boot", "spring-boot-starter-test") => "TestModule.Junit5"
        case ("com.lihaoyi", "utest") => "TestModule.Utest"
        case ("com.disneystreaming", "weaver-scalacheck") => "TestModule.Weaver"
        case ("dev.zio", "zio-test" | "zio-test-sbt") => "TestModule.ZioTest"
        case ("org.scalacheck", _) => "TestModule.ScalaCheck"
      }
    }
  }
}
