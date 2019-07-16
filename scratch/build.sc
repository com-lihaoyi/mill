import mill.scalalib.{SbtModule, Dep, DepSyntax}
//import $ivy.`com.lihaoyi::mill-contrib-bsp:0.4.1-21-fd34d3-DIRTY61e11e70`

trait BetterFilesModule extends SbtModule{
  def scalaVersion = "2.12.4"
  def scalacOptions = Seq(
    "-deprecation",                      // Emit warning and location for usages of deprecated APIs.
    "-encoding", "utf-8",                // Specify character encoding used by source files.
    "-explaintypes",                     // Explain type errors in more detail.
    "-feature",                          // Emit warning and location for usages of features that should be imported explicitly.
    "-Ywarn-infer-any",                  // Warn when a type argument is inferred to be `Any`.
    "-Ywarn-nullary-override",           // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Ywarn-nullary-unit",               // Warn when nullary methods return Unit.
    "-Ywarn-numeric-widen",              // Warn when numerics are widened.
    "-Ywarn-unused:implicits",           // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports",             // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals",              // Warn if a local definition is unused.
    "-Ywarn-unused:params",              // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars",             // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates",            // Warn if a private member is unused.
    "-Ywarn-value-discard"               // Warn when non-Unit expression results are unused.
  )
  override def javacOptions = Seq("-source", "1.8", "-target", "1.8", "-Xlint")

  object test extends Tests{
    def moduleDeps =
      if (this == core.test) super.moduleDeps
      else super.moduleDeps ++ Seq(core.test)
    def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.0.4")
    def testFrameworks = Seq("org.scalatest.tools.Framework")
  }
}

object core extends BetterFilesModule

object bsp extends BetterFilesModule{
  def moduleDeps = Seq(core)
  def ivyDeps = Agg(ivy"ch.epfl.scala:bsp4j:2.0.0-M3")
}

object benchmarks extends BetterFilesModule{
  def moduleDeps = Seq(core)
  def ivyDeps = Agg(
    ivy"commons-io:commons-io:2.5"
  )
  def unmanagedClasspath = Agg(
    mill.modules.Util.download(
      "https://github.com/williamfiset/FastJavaIO/releases/download/v1.0/fastjavaio.jar",
      "fastjavaio.jar"
    )
  )
}