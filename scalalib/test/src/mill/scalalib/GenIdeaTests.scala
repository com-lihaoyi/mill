package mill.scalalib

import ammonite.ops._
import mill._

import mill.util.{TestEvaluator, TestUtil}
import utest._

object GenIdeaTests extends TestSuite {

  val basePath = pwd / 'target / 'workspace / "gen-idea"
  val outPath = basePath / 'out
  val workingSrcPath = basePath / 'src

  trait HelloWorldModule extends scalalib.ScalaModule {
    def scalaVersion = "2.12.4"
    def basePath = HelloWorldTests.workingSrcPath
  }

  object HelloWorld extends TestUtil.BaseModule with HelloWorldModule

  val helloWorldEvaluator = new TestEvaluator(
    HelloWorld,
    outPath,
    workingSrcPath
  )

  def tests: Tests = Tests {
    'genIdeaTests - {
      helloWorldEvaluator(HelloWorld.scalaVersion)
      val x = GenIdea.xmlFileLayout(helloWorldEvaluator.evaluator, HelloWorld)
      val pp = new scala.xml.PrettyPrinter(999, 4)

      for((relPath, xml) <- GenIdea.xmlFileLayout(helloWorldEvaluator.evaluator, HelloWorld)){
        write.over(basePath/ "generated"/ relPath, pp.format(xml))
      }

      Seq(
        "gen-idea/idea_modules/iml" ->
          basePath / "generated" / ".idea_modules" /".iml",
        "gen-idea/idea_modules/root.iml" ->
          basePath / "generated" / ".idea_modules" /"root.iml",
        "gen-idea/idea/libraries/scala-reflect_2.12.4_scala-reflect-2.12.4-sources.jar.xml" ->
          basePath / "generated" / ".idea" / "libraries" / "scala-reflect_2.12.4_scala-reflect-2.12.4-sources.jar.xml",
        "gen-idea/idea/libraries/scala-reflect_2.12.4_scala-reflect-2.12.4.jar.xml" ->
          basePath / "generated" / ".idea" / "libraries" / "scala-reflect_2.12.4_scala-reflect-2.12.4.jar.xml",
        "gen-idea/idea/libraries/scala-library_2.12.4_scala-library-2.12.4.jar.xml" ->
          basePath / "generated" / ".idea" / "libraries" / "scala-library_2.12.4_scala-library-2.12.4.jar.xml",
        "gen-idea/idea/libraries/modules_scala-xml_2.12_1.0.6_scala-xml_2.12-1.0.6.jar.xml" ->
          basePath / "generated" / ".idea" / "libraries" / "modules_scala-xml_2.12_1.0.6_scala-xml_2.12-1.0.6.jar.xml",
        "gen-idea/idea/libraries/modules_scala-xml_2.12_1.0.6_scala-xml_2.12-1.0.6-sources.jar.xml" ->
          basePath / "generated" / ".idea" / "libraries" / "modules_scala-xml_2.12_1.0.6_scala-xml_2.12-1.0.6-sources.jar.xml",
        "gen-idea/idea/libraries/scala-compiler_2.12.4_scala-compiler-2.12.4-sources.jar.xml" ->
          basePath / "generated" / ".idea" / "libraries" / "scala-compiler_2.12.4_scala-compiler-2.12.4-sources.jar.xml",
        "gen-idea/idea/libraries/scala-library_2.12.4_scala-library-2.12.4-sources.jar.xml" ->
          basePath / "generated" / ".idea" / "libraries" / "scala-library_2.12.4_scala-library-2.12.4-sources.jar.xml",
        "gen-idea/idea/libraries/scala-compiler_2.12.4_scala-compiler-2.12.4.jar.xml" ->
          basePath / "generated" / ".idea" / "libraries" / "scala-compiler_2.12.4_scala-compiler-2.12.4.jar.xml",
        "gen-idea/idea/modules.xml" ->
          basePath / "generated" / ".idea" / "modules.xml",
        "gen-idea/idea/misc.xml" ->
          basePath / "generated" / ".idea" / "misc.xml",
      ).foreach { case (resource, generated) =>
          println("checking "+resource)
          val resourceString = scala.io.Source.fromResource(resource).getLines().mkString("\n")
          val generatedString = normaliseLibraryPaths(read! generated)

          assert(resourceString == generatedString)
        }
    }
  }


  private val libPathRegex =  """([\w/]+)/.coursier""".r
  private def normaliseLibraryPaths(in: String): String = {
    libPathRegex.replaceAllIn(in, "COURSIER_HOME")
  }
}
