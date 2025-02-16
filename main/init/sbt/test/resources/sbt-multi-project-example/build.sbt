name := "sbt-multi-project-example"
organization in ThisBuild := "com.pbassiner"
scalaVersion in ThisBuild := "2.12.3"

// PROJECTS

lazy val global = project
  .in(file("."))
  .settings(settings)
  .disablePlugins(AssemblyPlugin)
  .aggregate(
    common,
    multi1,
    multi2
  )

lazy val common = project
  .settings(
    name := "common",
    settings,
    libraryDependencies ++= commonDependencies
  )
  .disablePlugins(AssemblyPlugin)

lazy val multi1 = project
  .settings(
    name := "multi1",
    settings,
    assemblySettings,
    libraryDependencies ++= commonDependencies ++ Seq(
      dependencies.monocleCore,
      dependencies.monocleMacro
    )
  )
  .dependsOn(
    common
  )

lazy val multi2 = project
  .settings(
    name := "multi2",
    settings,
    assemblySettings,
    libraryDependencies ++= commonDependencies ++ Seq(
      dependencies.pureconfig
    )
  )
  .dependsOn(
    common
  )

// DEPENDENCIES

lazy val dependencies =
  new {
    val logbackV        = "1.2.3"
    val logstashV       = "4.11"
    val scalaLoggingV   = "3.7.2"
    val slf4jV          = "1.7.25"
    val typesafeConfigV = "1.3.1"
    val pureconfigV     = "0.8.0"
    val monocleV        = "1.4.0"
    val akkaV           = "2.5.6"
    val scalatestV      = "3.0.4"
    val scalacheckV     = "1.13.5"

    val logback        = "ch.qos.logback"             % "logback-classic"          % logbackV
    val logstash       = "net.logstash.logback"       % "logstash-logback-encoder" % logstashV
    val scalaLogging   = "com.typesafe.scala-logging" %% "scala-logging"           % scalaLoggingV
    val slf4j          = "org.slf4j"                  % "jcl-over-slf4j"           % slf4jV
    val typesafeConfig = "com.typesafe"               % "config"                   % typesafeConfigV
    val akka           = "com.typesafe.akka"          %% "akka-stream"             % akkaV
    val monocleCore    = "com.github.julien-truffaut" %% "monocle-core"            % monocleV
    val monocleMacro   = "com.github.julien-truffaut" %% "monocle-macro"           % monocleV
    val pureconfig     = "com.github.pureconfig"      %% "pureconfig"              % pureconfigV
    val scalatest      = "org.scalatest"              %% "scalatest"               % scalatestV
    val scalacheck     = "org.scalacheck"             %% "scalacheck"              % scalacheckV
  }

lazy val commonDependencies = Seq(
  dependencies.logback,
  dependencies.logstash,
  dependencies.scalaLogging,
  dependencies.slf4j,
  dependencies.typesafeConfig,
  dependencies.akka,
  dependencies.scalatest  % "test",
  dependencies.scalacheck % "test"
)

// SETTINGS

lazy val settings =
commonSettings ++
wartremoverSettings ++
scalafmtSettings

lazy val compilerOptions = Seq(
  "-unchecked",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:postfixOps",
  "-deprecation",
  "-encoding",
  "utf8"
)

lazy val commonSettings = Seq(
  scalacOptions ++= compilerOptions,
  resolvers ++= Seq(
    "Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository",
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots")
  )
)

lazy val wartremoverSettings = Seq(
  wartremoverWarnings in (Compile, compile) ++= Warts.allBut(Wart.Throw)
)

lazy val scalafmtSettings =
  Seq(
    scalafmtOnCompile := true,
    scalafmtTestOnCompile := true,
    scalafmtVersion := "1.2.0"
  )

lazy val assemblySettings = Seq(
  assemblyJarName in assembly := name.value + ".jar",
  assemblyMergeStrategy in assembly := {
    case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    case "application.conf"            => MergeStrategy.concat
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  }
)
