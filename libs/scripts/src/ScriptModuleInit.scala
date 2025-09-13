package mill.scripts
import mill.*
import mill.api.{Result, Discover}
object ScriptModuleInit extends ((String, Map[String, String]) => Option[Result[mill.api.ExternalModule]]) {
  def apply(millFileString: String, env: Map[String, String]) = {
    val workspace = mill.api.BuildCtx.workspaceRoot
    val millFile = os.Path(millFileString, workspace)
    mill.constants.DebugLog.println("millFileString " + millFileString)
    mill.constants.DebugLog.println("millFile " + millFile)
    mill.constants.DebugLog.println("os.exists(millFile) " + os.exists(millFile))
    Option.when(os.exists(millFile)) {
      Result.create {
        val headerData = mill.constants.Util.readBuildHeader(millFile.toNIO, millFile.last, true)
        lazy val parsedHeaderData =
          upickle.read[Map[String, ujson.Value]](mill.internal.Util.parseHeaderData(headerData))

        val testTarget = headerData.linesIterator.collectFirst { case s"tests: $target" => target }
        val testTrait = headerData.linesIterator.collectFirst { case s"testTrait: $target" => target }
        val bootstrapModule = testTarget match {
          case None => millFile.ext match {
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
          case Some(targetName) =>
            val targetPath = millFile / os.up / targetName
            import mill.javalib.TestModule.*
            millFile.ext match {
              case "java" =>
                val targetYamlHeader =
                  mill.constants.Util.readBuildHeader(targetPath.toNIO, targetPath.last, true)
                val targetModule = new ScriptModule.Java(targetPath) with ScriptModule.Publish {
                  override lazy val millDiscover = Discover[this.type]

                  override def buildOverrides = parsedHeaderData
                }
                val testModule = testTrait match {
                  case Some("TestNG") =>
                    new ScriptModule.Java(millFile) with targetModule.JavaTests with TestNg {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Junit4") =>
                    new ScriptModule.Java(millFile) with targetModule.JavaTests with Junit4 {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Junit5") =>
                    new ScriptModule.Java(millFile) with targetModule.JavaTests with Junit5 {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                }
                testModule

              case "scala" =>
                val targetYamlHeader =
                  mill.constants.Util.readBuildHeader(targetPath.toNIO, targetPath.last, true)
                val targetModule = new ScriptModule.Scala(targetPath) with ScriptModule.Publish {
                  override lazy val millDiscover = Discover[this.type]

                  override def buildOverrides = parsedHeaderData
                }
                val testModule = testTrait match {
                  case Some("TestNG") =>
                    new ScriptModule.Scala(millFile) with targetModule.ScalaTests with TestNg {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Junit4") =>
                    new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Junit4 {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Junit5") =>
                    new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Junit5 {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Scalatest") =>
                    new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ScalaTest {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Specs2") =>
                    new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Specs2 {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Utest") =>
                    new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Utest {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Munit") =>
                    new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Munit {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Weaver") =>
                    new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Weaver {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("ZioTest") =>
                    new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ZioTest {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("ScalaCheck") =>
                    new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ScalaCheck {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                }
                testModule

              case "kt" =>
                val targetYamlHeader =
                  mill.constants.Util.readBuildHeader(targetPath.toNIO, targetPath.last, true)
                val targetModule = new ScriptModule.Kotlin(targetPath) with ScriptModule.Publish {
                  override lazy val millDiscover = Discover[this.type]
                }
                val testModule = testTrait match {
                  case Some("TestNG") =>
                    new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with TestNg {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Junit4") =>
                    new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with Junit4 {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                  case Some("Junit5") =>
                    new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with Junit5 {
                      override lazy val millDiscover = Discover[this.type]

                      override def buildOverrides = parsedHeaderData
                    }
                }
                testModule
            }
        }
        bootstrapModule
      }
    }
  }
}
