import mill._, scalalib._
//import $ivy.`com.lihaoyi::mill-contrib-bsp:0.4.1-16-c95bc4-DIRTYd5dc7fa5`

object mill_exercise extends ScalaModule {
  def scalaVersion = "2.12.8"
  def mainClass = Some("mill_exercise.Compiler")
  def ivyDeps = Agg(
    ivy"org.scalameta::metals:0.5.2",
    ivy"org.scalameta::scalameta:4.1.9",
    ivy"com.geirsson::coursier-small:1.3.3",
    ivy"org.scala-lang:scala-reflect:2.12.8",
    ivy"org.scala-lang:scala-compiler:2.12.8",
    ivy"org.eclipse.lsp4j:org.eclipse.lsp4j:0.7.1",
    ivy"ch.epfl.scala:bsp4j:2.0.0-M3",
    ivy"com.google.code.gson:gson:2.3.1",
    ivy"com.lihaoyi::ammonite-ops:1.6.7"
  )

  object test extends Tests {
    def ivyDeps = Agg(//ivy"org.scalameta::metals:0.5.2",
                      ivy"org.scalatest::scalatest:3.0.4",
                      ivy"org.scalactic::scalactic:3.0.5",
                      ivy"org.scalameta::testkit:4.1.9",
                      ivy"org.eclipse.lsp4j:org.eclipse.lsp4j:0.5.0",
                      ivy"ch.epfl.scala::bloop-config:1.2.5",
                      ivy"org.scala-lang.modules::scala-java8-compat:0.9.0")

    def testFrameworks = Seq("org.scalatest.tools.Framework")
  }
}

object random extends SbtModule {

  def scalacOptions = Seq(
    //"-Ywarn-unused",
    "-Ylog-classpath"
  )

  def scalaVersion = "2.12.8"

  def ivyDeps = Agg(ivy"ch.epfl.scala:bsp4j:2.0.0-M3")
}
