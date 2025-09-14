package mill.singlefile
import mill.*
import mill.api.{Discover, ExternalModule, Result}
import mill.scalalib.ScalaModule
import mill.kotlinlib.KotlinModule
import mill.singlefile.SingleFileModule.parseHeaderData

object SingleFileModuleInit extends ((String, Map[String, String]) => Option[Result[mill.api.ExternalModule]]) {
  def instantiate(className: String, args: AnyRef*): ExternalModule = {
    Class.forName(className).getDeclaredConstructors.head.newInstance(args*).asInstanceOf[ExternalModule]
  }
  def moduleFor(millFile: os.Path) = millFile.ext match {
    case "java" => instantiate("mill.singlefile.Java", millFile)
    case "scala" => instantiate("mill.singlefile.Scala", millFile)
    case "kt" => instantiate("mill.singlefile.Kotlin", millFile)
  }

  def testModuleFor(millFile: os.Path, targetName: String, testTrait: String) = {
    val parsedHeaderData = parseHeaderData(millFile)
    val targetPath = millFile / os.up / targetName
    import mill.javalib.TestModule.*

    millFile.ext match {
      case "java" =>
        val targetModule = moduleFor(targetPath)
        testTrait match {
          case "TestNg" => instantiate("mill.singlefile.Java.TestNg", millFile, targetModule)
          case "Junit4" => instantiate("mill.singlefile.Java.Junit4", millFile, targetModule)
          case "Junit5" => instantiate("mill.singlefile.Java.Junit5", millFile, targetModule)
        }

      case "scala" =>
        val targetModule = moduleFor(targetPath).asInstanceOf[ScalaModule]
        testTrait match {
          case "TestNg" => instantiate("mill.singlefile.Scala.TestNg", millFile, targetModule)
          case "Junit4" => instantiate("mill.singlefile.Scala.Junit4", millFile, targetModule)
          case "Junit5" => instantiate("mill.singlefile.Scala.Junit5", millFile, targetModule)
          case "ScalaTest" => instantiate("mill.singlefile.Scala.ScalaTest", millFile, targetModule)
          case "Specs2" => instantiate("mill.singlefile.Scala.Specs2", millFile, targetModule)
          case "Utest" => instantiate("mill.singlefile.Scala.Utest", millFile, targetModule)
          case "Munit" => instantiate("mill.singlefile.Scala.Munit", millFile, targetModule)
          case "Weaver" => instantiate("mill.singlefile.Scala.Weaver", millFile, targetModule)
          case "ZioTest" => instantiate("mill.singlefile.Scala.ZioTest", millFile, targetModule)
          case "ScalaCheck" => instantiate("mill.singlefile.Scala.ScalaCheck", millFile, targetModule)
        }

      case "kt" =>
        val targetModule = moduleFor(targetPath).asInstanceOf[KotlinModule]
        testTrait match {
          case "TestNg" => instantiate("mill.singlefile.Kotlin.TestNg", millFile, targetModule)
          case "Junit4" => instantiate("mill.singlefile.Kotlin.Junit4", millFile, targetModule)
          case "Junit5" => instantiate("mill.singlefile.Kotlin.Junit5", millFile, targetModule)
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
