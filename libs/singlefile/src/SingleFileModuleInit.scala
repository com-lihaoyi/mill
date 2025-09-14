package mill.singlefile
import mill.*
import mill.api.{Result, Discover}
import mill.scalalib.ScalaModule
import mill.kotlinlib.KotlinModule
import mill.singlefile.SingleFileModule.parseHeaderData

object SingleFileModuleInit extends ((String, Map[String, String]) => Option[Result[mill.api.ExternalModule]]) {
  def moduleFor(millFile: os.Path) = millFile.ext match {
    case "java" => new Java(millFile)
    case "scala" => new Scala(millFile)
    case "kt" => new Kotlin(millFile)
  }

  def testModuleFor(millFile: os.Path, targetName: String, testTrait: String) = {
    val parsedHeaderData = parseHeaderData(millFile)
    val targetPath = millFile / os.up / targetName
    import mill.javalib.TestModule.*

    millFile.ext match {
      case "java" =>
        val targetModule = moduleFor(targetPath)
        testTrait match {
          case "TestNg" => new Java.TestNg(millFile, targetModule)
          case "Junit4" => new Java.Junit4(millFile, targetModule)
          case "Junit5" => new Java.Junit5(millFile, targetModule)
        }

      case "scala" =>
        val targetModule = moduleFor(targetPath).asInstanceOf[ScalaModule]
        testTrait match {
          case "TestNg" => new Scala.TestNg(millFile, targetModule)
          case "Junit4" => new Scala.Junit4(millFile, targetModule)
          case "Junit5" => new Scala.Junit5(millFile, targetModule)
          case "ScalaTest" => new Scala.ScalaTest(millFile, targetModule)
          case "Specs2" => new Scala.Specs2(millFile, targetModule)
          case "Utest" => new Scala.Utest(millFile, targetModule)
          case "Munit" => new Scala.Munit(millFile, targetModule)
          case "Weaver" => new Scala.Weaver(millFile, targetModule)
          case "ZioTest" => new Scala.ZioTest(millFile, targetModule)
          case "ScalaCheck" => new Scala.ScalaCheck(millFile, targetModule)
        }

      case "kt" =>
        val targetModule = moduleFor(targetPath).asInstanceOf[KotlinModule]
        testTrait match {
          case "TestNg" => new Kotlin.TestNg(millFile, targetModule)
          case "Junit4" => new Kotlin.Junit4(millFile, targetModule)
          case "Junit5" => new Kotlin.Junit5(millFile, targetModule)
        }
    }
  }

  def apply(millFileString: String, env: Map[String, String]) = {
    val workspace = mill.api.BuildCtx.workspaceRoot
    val millFile = os.Path(millFileString, workspace)

    Option.when(os.exists(millFile)) {
      Result.create {
        val headerData = mill.constants.Util.readBuildHeader(millFile.toNIO, millFile.last, true)


        val testTarget = headerData.linesIterator.collectFirst { case s"tests: $target" => target }
        val testTrait = headerData.linesIterator.collectFirst { case s"testTrait: $target" => target }
        testTarget match {
          case None => moduleFor(millFile)
          case Some(targetName) => testModuleFor(millFile, targetName, testTrait.get)
        }
      }
    }
  }
}
