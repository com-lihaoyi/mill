import mill._, mill.scalalib._, mill.scalalib.publish._, mill.scalajslib._
import $ivy.`org.scalariform::scalariform:0.2.5`
import $file.version

import ammonite.ops._

val ScalaVersions = Seq("2.10.7", "2.11.12", "2.12.4", "2.13.0-M2")

trait Scalariform extends ScalaModule {
  import scalariform.formatter._
  import scalariform.formatter.preferences._

  val defaultPreferences = FormattingPreferences()

  val playJsonPreferences = defaultPreferences
    .setPreference(SpacesAroundMultiImports, true)
    .setPreference(SpaceInsideParentheses, false)
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(PreserveSpaceBeforeArguments, true)
    .setPreference(DoubleIndentConstructorArguments, false)

  def reformat = T {
    val files = sources().filter(p => exists(p.path)).flatMap(f => ls.rec(f.path)).filterNot(_.isDir)
    T.ctx.log.info(s"Formatting ${files.size} Scala sources")
    files.foreach { path =>
      val formatted = ScalaFormatter.format(
        read(path),
        playJsonPreferences,
        scalaVersion = scalaVersion()
      )
      write.over(path, formatted)
    }
    PathRef(pwd)
  }
}

trait MiMa extends ScalaModule {
  def previousVersions = T {
    scalaVersion().split('.')(1) match {
      case "10" => Seq("2.6.0")
      case "11" => Seq("2.6.0")
      case "12" => Seq("2.6.0")
      case _ => Nil
    }
  }
}

trait PlayJsonModule extends CrossSbtModule with PublishModule with Scalariform {

  def compile = T {
    reformat()
    super.compile()
  }

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

  def generatedSources = T {
    import ammonite.ops._

    val dir = T.ctx().dest
    mkdir(dir / "play-json")
    val file = dir / "play-json" / "Generated.scala"

    val (reads, writes) = (1 to 22).map { i =>
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
  def moduleDeps = Seq(playFunctional(crossScalaVersion))

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
    def ivyDeps = T {
      val specsVersion = Lib.scalaBinaryVersion(scalaVersion()) match {
        case "2.10" => "3.9.1"
        case _ => "4.0.2"
      }
      Agg(
        ivy"org.scalatest::scalatest:3.0.5-M1",
        ivy"org.scalacheck::scalacheck:1.13.5",
        ivy"com.chuusai::shapeless:2.3.3",
        ivy"ch.qos.logback:logback-classic:1.2.3",
        ivy"org.specs2::specs2-core:$specsVersion"
      )
    }

    def sources = T.sources(
      millSourcePath / platformSegment / "src" / "test",
      millSourcePath / "shared" / "src" / "test"
    )

//    def testFramework = "org.scalatest.tools.Framework"
    def testFramework = "org.specs2.runner.Specs2Framework"
  }

}

object playJsonJs extends Cross[PlayJsonJs](ScalaVersions:_*)
class PlayJsonJs(val crossScalaVersion: String) extends PlayJson("js") with ScalaJSModule {
  def moduleDeps = Seq(playFunctional(crossScalaVersion))

  def scalaJSVersion = "0.6.22"
}

object playFunctional extends Cross[PlayFunctional](ScalaVersions:_*)
class PlayFunctional(val crossScalaVersion: String) extends PlayJsonModule {
  def millSourcePath = pwd / "play-functional"
  def artifactName = "play-functional"
}

object playJoda extends Cross[PlayJoda](ScalaVersions:_*)
class PlayJoda(val crossScalaVersion: String) extends PlayJsonModule {
  def moduleDeps = Seq(playJsonJvm(crossScalaVersion))

  def millSourcePath = pwd / "play-json-joda"
  def artifactName = "play-json-joda"

  def ivyDeps = Agg(
    ivy"joda-time:joda-time:2.9.9"
  )
}

def release() = T.command {
  println(s"version: ${version.current} released!")
}
