import ReleaseTransformations._

lazy val previousJawnVersion = "0.10.4"

lazy val stableCrossVersions =
  Seq("2.10.6", "2.11.11", "2.12.2")

// we'll support 2.13.0-M1 soon but not yet
lazy val allCrossVersions =
  stableCrossVersions

lazy val benchmarkVersion =
  "2.12.2"

lazy val jawnSettings = Seq(
  organization := "org.spire-math",
  scalaVersion := "2.12.2",
  crossScalaVersions := allCrossVersions,

  mimaPreviousArtifacts := Set(organization.value %% moduleName.value % previousJawnVersion),

  resolvers += Resolver.sonatypeRepo("releases"),

  libraryDependencies ++=
    "org.scalatest" %% "scalatest" % "3.0.3" % Test ::
    "org.scalacheck" %% "scalacheck" % "1.13.5" % Test ::
    Nil,

  scalacOptions ++=
    "-deprecation" ::
    "-optimize" ::
    "-unchecked" ::
    Nil,

  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  homepage := Some(url("http://github.com/non/jawn")),

  // release stuff
  releaseCrossBuild := true,
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := Function.const(false),

  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value) {
      Some("Snapshots" at nexus + "content/repositories/snapshots")
    } else {
      Some("Releases" at nexus + "service/local/staging/deploy/maven2")
    }
  },

  scmInfo := Some(ScmInfo(
    browseUrl = url("https://github.com/non/jawn"),
    connection = "scm:git:git@github.com:non/jawn.git"
  )),

  developers += Developer(
    name = "Erik Osheim",
    email = "erik@plastic-idolatry.com",
    id = "d_m",
    url = url("http://github.com/non/")
  ),

  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    ReleaseHelper.runCommandAndRemaining("+test"), // formerly runTest
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    ReleaseHelper.runCommandAndRemaining("+publishSigned"),
    setNextVersion,
    commitNextVersion,
    ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
    pushChanges))

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false,
  mimaPreviousArtifacts := Set())

lazy val root = project.in(file("."))
  .aggregate(all.map(Project.projectToRef): _*)
  .enablePlugins(CrossPerProjectPlugin)
  .disablePlugins(JmhPlugin)
  .settings(name := "jawn")
  .settings(jawnSettings: _*)
  .settings(noPublish: _*)

lazy val parser = project.in(file("parser"))
  .settings(name := "parser")
  .settings(moduleName := "jawn-parser")
  .settings(jawnSettings: _*)
  .disablePlugins(JmhPlugin)

lazy val util = project.in(file("util"))
  .dependsOn(parser % "compile->compile;test->test")
  .settings(name := "util")
  .settings(moduleName := "jawn-util")
  .settings(jawnSettings: _*)
  .disablePlugins(JmhPlugin)

lazy val ast = project.in(file("ast"))
  .dependsOn(parser % "compile->compile;test->test")
  .dependsOn(util % "compile->compile;test->test")
  .settings(name := "ast")
  .settings(moduleName := "jawn-ast")
  .settings(jawnSettings: _*)
  .disablePlugins(JmhPlugin)

def support(s: String) =
  Project(id = s, base = file(s"support/$s"))
    .settings(name := (s + "-support"))
    .settings(moduleName := "jawn-" + s)
    .dependsOn(parser)
    .settings(jawnSettings: _*)
    .disablePlugins(JmhPlugin)

lazy val supportArgonaut = support("argonaut")
  .settings(crossScalaVersions := stableCrossVersions)
  .settings(libraryDependencies += "io.argonaut" %% "argonaut" % "6.2")

lazy val supportJson4s = support("json4s")
  .dependsOn(util)
  .settings(crossScalaVersions := stableCrossVersions)
  .settings(libraryDependencies += "org.json4s" %% "json4s-ast" % "3.5.2")

lazy val supportPlay = support("play")
  .settings(crossScalaVersions := stableCrossVersions)
  .settings(libraryDependencies += (scalaBinaryVersion.value match {
    case "2.10" => "com.typesafe.play" %% "play-json" % "2.4.11"
    case "2.11" =>  "com.typesafe.play" %% "play-json" % "2.5.15"
    case _ =>  "com.typesafe.play" %% "play-json" % "2.6.0"
  }))

lazy val supportRojoma = support("rojoma")
  .settings(crossScalaVersions := stableCrossVersions)
  .settings(libraryDependencies += "com.rojoma" %% "rojoma-json" % "2.4.3")

lazy val supportRojomaV3 = support("rojoma-v3")
  .settings(crossScalaVersions := stableCrossVersions)
  .settings(libraryDependencies += "com.rojoma" %% "rojoma-json-v3" % "3.7.2")

lazy val supportSpray = support("spray")
  .settings(crossScalaVersions := stableCrossVersions)
  .settings(resolvers += "spray" at "http://repo.spray.io/")
  .settings(libraryDependencies += "io.spray" %% "spray-json" % "1.3.3")

lazy val benchmark = project.in(file("benchmark"))
  .dependsOn(all.map(Project.classpathDependency[Project]): _*)
  .settings(name := "jawn-benchmark")
  .settings(jawnSettings: _*)
  .settings(scalaVersion := benchmarkVersion)
  .settings(crossScalaVersions := Seq(benchmarkVersion))
  .settings(noPublish: _*)
  .enablePlugins(JmhPlugin)

lazy val all =
  Seq(parser, util, ast, supportArgonaut, supportJson4s, supportPlay, supportRojoma, supportRojomaV3, supportSpray)
