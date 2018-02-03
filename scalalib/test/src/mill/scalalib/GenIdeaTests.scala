package mill.scalalib

import ammonite.ops._
import mill._
import mill.define.Discover
import mill.util.{TestEvaluator, TestUtil}
import utest._
import mill.util.TestEvaluator.implicitDisover
object GenIdeaTests extends TestSuite {

  val millSourcePath = pwd / 'target / 'workspace / "gen-idea"
  val outPath = millSourcePath / 'out
  val workingSrcPath = millSourcePath / 'src

  trait HelloWorldModule extends scalalib.ScalaModule {
    def scalaVersion = "2.12.4"
    def millSourcePath = HelloWorldTests.workingSrcPath
  }

  object HelloWorld extends TestUtil.BaseModule with HelloWorldModule

  val helloWorldEvaluator = TestEvaluator.static(HelloWorld)

  def tests: Tests = Tests {
    'genIdeaTests - {
      helloWorldEvaluator(HelloWorld.scalaVersion)
      val pp = new scala.xml.PrettyPrinter(999, 4)

      val layout = GenIdea.xmlFileLayout(helloWorldEvaluator.evaluator, HelloWorld, fetchMillModules = false)
      for((relPath, xml) <- layout){
        write.over(millSourcePath/ "generated"/ relPath, pp.format(xml))
      }

      Seq(
        "gen-idea/idea_modules/iml" ->
          millSourcePath / "generated" / ".idea_modules" /".iml",
        "gen-idea/idea_modules/root.iml" ->
          millSourcePath / "generated" / ".idea_modules" /"root.iml",
        "gen-idea/idea/libraries/scala-reflect_2.12.4_scala-reflect-2.12.4-sources.jar.xml" ->
          millSourcePath / "generated" / ".idea" / "libraries" / "scala-reflect_2.12.4_scala-reflect-2.12.4-sources.jar.xml",
        "gen-idea/idea/libraries/scala-reflect_2.12.4_scala-reflect-2.12.4.jar.xml" ->
          millSourcePath / "generated" / ".idea" / "libraries" / "scala-reflect_2.12.4_scala-reflect-2.12.4.jar.xml",
        "gen-idea/idea/libraries/scala-library_2.12.4_scala-library-2.12.4.jar.xml" ->
          millSourcePath / "generated" / ".idea" / "libraries" / "scala-library_2.12.4_scala-library-2.12.4.jar.xml",
        "gen-idea/idea/libraries/modules_scala-xml_2.12_1.0.6_scala-xml_2.12-1.0.6.jar.xml" ->
          millSourcePath / "generated" / ".idea" / "libraries" / "modules_scala-xml_2.12_1.0.6_scala-xml_2.12-1.0.6.jar.xml",
        "gen-idea/idea/libraries/modules_scala-xml_2.12_1.0.6_scala-xml_2.12-1.0.6-sources.jar.xml" ->
          millSourcePath / "generated" / ".idea" / "libraries" / "modules_scala-xml_2.12_1.0.6_scala-xml_2.12-1.0.6-sources.jar.xml",
        "gen-idea/idea/libraries/scala-compiler_2.12.4_scala-compiler-2.12.4-sources.jar.xml" ->
          millSourcePath / "generated" / ".idea" / "libraries" / "scala-compiler_2.12.4_scala-compiler-2.12.4-sources.jar.xml",
        "gen-idea/idea/libraries/scala-library_2.12.4_scala-library-2.12.4-sources.jar.xml" ->
          millSourcePath / "generated" / ".idea" / "libraries" / "scala-library_2.12.4_scala-library-2.12.4-sources.jar.xml",
        "gen-idea/idea/libraries/scala-compiler_2.12.4_scala-compiler-2.12.4.jar.xml" ->
          millSourcePath / "generated" / ".idea" / "libraries" / "scala-compiler_2.12.4_scala-compiler-2.12.4.jar.xml",
        "gen-idea/idea/modules.xml" ->
          millSourcePath / "generated" / ".idea" / "modules.xml",
        "gen-idea/idea/misc.xml" ->
          millSourcePath / "generated" / ".idea" / "misc.xml"
      ).foreach { case (resource, generated) =>
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
