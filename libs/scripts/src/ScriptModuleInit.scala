package mill.scripts
import mill.*
import mill.api.{Result, Discover}
import mill.scalalib.ScalaModule
import mill.kotlinlib.KotlinModule
object ScriptModuleInit extends ((String, Map[String, String]) => Option[Result[mill.api.ExternalModule]]) {
  def moduleFor(millFile: os.Path) = {
    val parsedHeaderData = parseHeaderData(millFile)
    millFile.ext match {
      case "java" =>
        new ScriptModule.Java(millFile) with ScriptModule.Publish {
          override lazy val millDiscover = Discover[this.type]
          override def buildOverrides = parsedHeaderData
        }
      case "scala" =>
        new ScriptModule.Scala(millFile) with ScriptModule.Publish {
          override lazy val millDiscover = Discover[this.type]
          override def buildOverrides = parsedHeaderData
        }
      case "kt" =>
        new ScriptModule.Kotlin(millFile) with ScriptModule.Publish {
          override lazy val millDiscover = Discover[this.type]
          override def buildOverrides = parsedHeaderData
        }
    }
  }

  def testModuleFor(millFile: os.Path, targetName: String, testTrait: String) = {
    val parsedHeaderData = parseHeaderData(millFile)
    val targetPath = millFile / os.up / targetName
    import mill.javalib.TestModule.*

    millFile.ext match {
      case "java" =>
        val targetModule = moduleFor(targetPath)
        testTrait match {
          case "TestNG" =>
            new ScriptModule.Java(millFile) with targetModule.JavaTests with TestNg {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Junit4" =>
            new ScriptModule.Java(millFile) with targetModule.JavaTests with Junit4 {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Junit5" =>
            new ScriptModule.Java(millFile) with targetModule.JavaTests with Junit5 {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
        }

      case "scala" =>
        val targetModule = moduleFor(targetPath).asInstanceOf[ScalaModule]
        testTrait match {
          case "TestNG" =>
            new ScriptModule.Scala(millFile) with targetModule.ScalaTests with TestNg {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Junit4" =>
            new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Junit4 {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Junit5" =>
            new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Junit5 {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Scalatest" =>
            new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ScalaTest {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Specs2" =>
            new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Specs2 {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Utest" =>
            new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Utest {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Munit" =>
            new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Munit {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Weaver" =>
            new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Weaver {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "ZioTest" =>
            new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ZioTest {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "ScalaCheck" =>
            new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ScalaCheck {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
        }

      case "kt" =>
        val targetModule = moduleFor(targetPath).asInstanceOf[KotlinModule]
        testTrait match {
          case "TestNG" =>
            new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with TestNg {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Junit4" =>
            new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with Junit4 {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
          case "Junit5" =>
            new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with Junit5 {
              override lazy val millDiscover = Discover[this.type]

              override def buildOverrides = parsedHeaderData
            }
        }
    }

  }
  def parseHeaderData(millFile: os.Path) = {
    val headerData = mill.constants.Util.readBuildHeader(millFile.toNIO, millFile.last, true)
    upickle.read[Map[String, ujson.Value]](mill.internal.Util.parseHeaderData(headerData))
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
