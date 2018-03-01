import mill._, mill.scalalib._, mill.scalalib.publish._, mill.scalajslib._
import $file.version
import $file.reformat
import reformat.Scalariform
import $file.mima
import mima.MiMa
import com.typesafe.tools.mima.core._

import ammonite.ops._
import mill.define.Task

val ScalaVersions = Seq("2.10.7", "2.11.12", "2.12.4", "2.13.0-M2")

trait PlayJsonModule extends CrossSbtModule with PublishModule with Scalariform with MiMa {

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.typesafe.play",
    url = "https://github.com/playframework/play-json",
    licenses = Seq(License("Apache-2.0", "http://www.apache.org/licenses/LICENSE-2.0.html")),
    scm = SCM(
      "https://github.com/playframework/play-json",
      "scm:git:git@github.com:playframework/play-json.git"
    ),
    developers = Seq(
      Developer(
        id = "playframework",
        name = "Play Framework Team",
        url = "https://github.com/playframework"
      )
    )
  )

  trait Tests extends super.Tests with Scalariform {
    val specs2Core = T {
      val v = Lib.scalaBinaryVersion(scalaVersion()) match {
        case "2.10" => "3.9.1"
        case _ => "4.0.2"
      }
      ivy"org.specs2::specs2-core:$v"
    }
  }

  def scalacOptions  = Seq("-deprecation", "-feature", "-unchecked", "-encoding", "utf8")
  def javacOptions = Seq("-encoding", "UTF-8", "-Xlint:-options")

  def publishVersion = version.current
}

abstract class PlayJson(val platformSegment: String) extends PlayJsonModule {
  def crossScalaVersion: String
  def millSourcePath = pwd /  "play-json"
  def artifactName = "play-json"

  def sources = T.sources(
    millSourcePath / platformSegment / "src" / "main",
    millSourcePath / "shared" / "src" / "main"
  )

  def ivyDeps = Agg(
    ivy"org.scala-lang:scala-reflect:${scalaVersion()}",
    ivy"org.typelevel::macro-compat::1.1.1"
  )

  def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(
    ivy"org.scalamacros:::paradise:2.1.0"
  )

  def mimaBinaryIssueFilters = Seq(
    // AbstractFunction1 is in scala.runtime and isn't meant to be used by end users
    ProblemFilters.exclude[MissingTypesProblem]("play.api.libs.json.JsArray$"),
    ProblemFilters.exclude[MissingTypesProblem]("play.api.libs.json.JsObject$"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("play.api.libs.json.DefaultWrites.BigIntWrites"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("play.api.libs.json.DefaultWrites.BigIntegerWrites"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("play.api.libs.json.DefaultReads.BigIntReads"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("play.api.libs.json.DefaultReads.BigIntegerReads"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("play.api.libs.json.DefaultWrites.BigIntWrites"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("play.api.libs.json.DefaultWrites.BigIntegerWrites"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("play.api.libs.json.DefaultReads.BigIntReads"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("play.api.libs.json.DefaultReads.BigIntegerReads"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("play.api.libs.json.JsonConfiguration.optionHandlers")
  )

  def generatedSources = T {
    import ammonite.ops._

    val dir = T.ctx().dest
    mkdir(dir / "play-json")
    val file = dir / "play-json" / "Generated.scala"

    val (writes, reads) = (1 to 22).map { i =>
      def commaSeparated(s: Int => String) = (1 to i).map(s).mkString(", ")

      def newlineSeparated(s: Int => String) = (1 to i).map(s).mkString("\n")

      val writerTypes = commaSeparated(j => s"T$j: Writes")
      val readerTypes = commaSeparated(j => s"T$j: Reads")
      val typeTuple = commaSeparated(j => s"T$j")
      val written = commaSeparated(j => s"implicitly[Writes[T$j]].writes(x._$j)")
      val readValues = commaSeparated(j => s"t$j")
      val readGenerators = newlineSeparated(j => s"t$j <- implicitly[Reads[T$j]].reads(arr(${j - 1}))")
      (
        s"""
          implicit def Tuple${i}W[$writerTypes]: Writes[Tuple${i}[$typeTuple]] = Writes[Tuple${i}[$typeTuple]](
            x => JsArray(Array($written))
          )
          """,
        s"""
          implicit def Tuple${i}R[$readerTypes]: Reads[Tuple${i}[$typeTuple]] = Reads[Tuple${i}[$typeTuple]]{
            case JsArray(arr) if arr.size == $i =>
              for{
                $readGenerators
              } yield Tuple$i($readValues)

            case _ =>
              JsError(Seq(JsPath() -> Seq(JsonValidationError("Expected array of $i elements"))))
          }
        """)
    }.unzip

    write(file, s"""
        package play.api.libs.json

        trait GeneratedReads {
          ${reads.mkString("\n")}
        }

        trait GeneratedWrites{
          ${writes.mkString("\n")}
        }
        """)

    Seq(PathRef(dir))
  }
}

object playJsonJvm extends Cross[PlayJsonJvm](ScalaVersions:_*)
class PlayJsonJvm(val crossScalaVersion: String) extends PlayJson("jvm") {
  def moduleDeps = Seq(playFunctionalJvm(crossScalaVersion))

  val jacksonVersion = "2.9.3"

  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"joda-time:joda-time:2.9.9",
    ivy"com.fasterxml.jackson.core:jackson-core:$jacksonVersion",
    ivy"com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion",
    ivy"com.fasterxml.jackson.core:jackson-databind:$jacksonVersion",
    ivy"com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion",
    ivy"com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion"
  )

  object test extends Tests {
    def ivyDeps =
      Agg(
        ivy"org.scalatest::scalatest:3.0.5-M1",
        ivy"org.scalacheck::scalacheck:1.13.5",
        ivy"com.chuusai::shapeless:2.3.3",
        ivy"ch.qos.logback:logback-classic:1.2.3",
        specs2Core()
      )

    def sources = {
      val docSpecs = ls.rec(millSourcePath / up / "docs" / "manual" / "working" / "scalaGuide").filter(_.isDir).filter(_.last=="code").map(PathRef(_))
      T.sources(
        docSpecs ++
        Seq(
          PathRef(millSourcePath / platformSegment / "src" / "test"),
          PathRef(millSourcePath / "shared" / "src" / "test")
        )
      )
    }

    def testFrameworks = Seq(
      "org.scalatest.tools.Framework",
      "org.specs2.runner.Specs2Framework"
    )
  }

}

object playJsonJs extends Cross[PlayJsonJs](ScalaVersions:_*)
class PlayJsonJs(val crossScalaVersion: String) extends PlayJson("js") with ScalaJSModule {
  def moduleDeps = Seq(playFunctionalJs(crossScalaVersion))

  def scalaJSVersion = "0.6.22"

  // TODO: remove super[PlayJson].Tests with super[ScalaJSModule].Tests hack
  object test extends super[PlayJson].Tests with super[ScalaJSModule].Tests with Scalariform {
    def ivyDeps =
      Agg(
        ivy"org.scalatest::scalatest::3.0.5-M1",
        ivy"org.scalacheck::scalacheck::1.13.5",
        ivy"com.chuusai::shapeless::2.3.3"
      )

    def sources = T.sources(
      millSourcePath / platformSegment / "src" / "test",
      millSourcePath / "shared" / "src" / "test"
    )

    def testFrameworks = Seq(
      "org.scalatest.tools.Framework"
    )
  }
}

trait PlayFunctional extends PlayJsonModule {
  def millSourcePath = pwd / "play-functional"
  def artifactName = "play-functional"
}

object playFunctionalJvm extends Cross[PlayFunctionalJvm](ScalaVersions:_*)
class PlayFunctionalJvm(val crossScalaVersion: String) extends PlayFunctional

object playFunctionalJs extends Cross[PlayFunctionalJs](ScalaVersions:_*)
class PlayFunctionalJs(val crossScalaVersion: String) extends PlayFunctional with ScalaJSModule {
  def scalaJSVersion = "0.6.22"
}

object playJoda extends Cross[PlayJoda](ScalaVersions:_*)
class PlayJoda(val crossScalaVersion: String) extends PlayJsonModule {
  def moduleDeps = Seq(playJsonJvm(crossScalaVersion))

  def millSourcePath = pwd / "play-json-joda"
  def artifactName = "play-json-joda"

  def ivyDeps = Agg(
    ivy"joda-time:joda-time:2.9.9"
  )

  object test extends Tests with Scalariform {
    def ivyDeps = Agg(specs2Core())

    def testFrameworks = Seq(
      "org.specs2.runner.Specs2Framework"
    )
  }

}

def release() = T.command {
  println(s"version: ${version.current} released!")
}

// TODO: we should have a way to "take all modules in this build"
val testModules = Seq(
  playJsonJvm("2.12.4").test,
  playJsonJs("2.12.4").test,
  playJoda("2.12.4").test
)

val sourceModules = Seq(
  playJsonJvm("2.12.4"),
  playJsonJs("2.12.4"),
  playJoda("2.12.4"),
)

val allModules = testModules ++ sourceModules

def reportBinaryIssues() = T.command {
  Task.traverse(sourceModules) { module =>
    module.mimaReportBinaryIssues.map(issues => module.millModuleSegments.render -> issues)
  }.map { issues =>
    val issuesByModules = issues.map { case (moduleName, issues) =>
      issues.foldLeft(Seq.empty[String]) { case (acc, (atrifact, problems)) =>
        val elem = if(problems.nonEmpty) {
          Some(
            s"""
               |Compared to artifact: ${atrifact}
               |found ${problems.size} binary incompatibilities:
               |${problems.mkString("\n")}
               """.stripMargin
          )
        } else {
          None
        }
        acc ++ elem
      }.mkString("\n")
    }
    if(issuesByModules.nonEmpty) {
      sys.error(issuesByModules.mkString("\n\n"))
    }
  }
}

def validateCode() = T.command {
  Task.traverse(allModules)(_.checkCodeFormat())
  // TODO: check headers
}
