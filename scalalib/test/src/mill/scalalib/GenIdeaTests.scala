package mill.scalalib

import coursier.Cache
import mill._
import mill.util.{TestEvaluator, TestUtil}
import utest._

object GenIdeaTests extends TestSuite {

  val millSourcePath = os.pwd / 'target / 'workspace / "gen-idea"

  trait HelloWorldModule extends scalalib.ScalaModule {
    def scalaVersion = "2.12.4"
    def millSourcePath = GenIdeaTests.millSourcePath
    object test extends super.Tests {
      def testFrameworks = Seq("utest.runner.Framework")
    }
  }

  object HelloWorld extends TestUtil.BaseModule with HelloWorldModule

  val helloWorldEvaluator = TestEvaluator.static(HelloWorld)

  def tests: Tests = Tests {
    'genIdeaTests - {
      val pp = new scala.xml.PrettyPrinter(999, 4)

      val layout = GenIdeaImpl.xmlFileLayout(
        helloWorldEvaluator.evaluator,
        HelloWorld,
        ("JDK_1_8", "1.8 (1)"), fetchMillModules = false)
      for((relPath, xml) <- layout){
        os.write.over(millSourcePath/ "generated"/ relPath, pp.format(xml))
      }

      Seq(
        "gen-idea/idea_modules/iml" ->
          millSourcePath / "generated" / ".idea_modules" /".iml",
        "gen-idea/idea_modules/test.iml" ->
          millSourcePath / "generated" / ".idea_modules" /"test.iml",
        "gen-idea/idea_modules/mill-build.iml" ->
          millSourcePath / "generated" / ".idea_modules" /"mill-build.iml",
        "gen-idea/idea/libraries/scala-library-2.12.4.jar.xml" ->
          millSourcePath / "generated" / ".idea" / "libraries" / "scala-library-2.12.4.jar.xml",
        "gen-idea/idea/modules.xml" ->
          millSourcePath / "generated" / ".idea" / "modules.xml",
        "gen-idea/idea/misc.xml" ->
          millSourcePath / "generated" / ".idea" / "misc.xml"
      ).foreach { case (resource, generated) =>
          val resourceString = scala.io.Source.fromResource(resource).getLines().mkString("\n")
          val generatedString = normaliseLibraryPaths(os.read(generated))

          assert(resourceString == generatedString)
        }
    }
  }


  private def normaliseLibraryPaths(in: String): String = {
    in.replaceAll(Cache.default.toPath.toAbsolutePath.toString, "COURSIER_HOME")
  }
}
