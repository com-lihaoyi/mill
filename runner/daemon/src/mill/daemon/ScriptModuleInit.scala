package mill.daemon
import mill.*
import mill.api.internal.RootModule
import mill.meta.ScriptModule
import mill.api.Discover
object ScriptModuleInit {
  def apply(projectRoot: os.Path, output: os.Path, millFile: os.Path) = {
    implicit val rootModuleInfo: RootModule.Info =
      new RootModule.Info(projectRoot, output, projectRoot)


    pprint.log(millFile)
    val yamlHeader = mill.constants.Util.readBuildHeader(millFile.toNIO, millFile.last, true)
    pprint.log(yamlHeader)
    val testTarget = yamlHeader.linesIterator.collectFirst { case s"tests: $target" => target }
    val testTrait = yamlHeader.linesIterator.collectFirst { case s"testTrait: $target" => target }
    pprint.log(testTarget)
    pprint.log(testTrait)
    val bootstrapModule = testTarget match {
      case None => millFile.ext match {
        case "java" =>
          println("A")
          new ScriptModule.Java(millFile) with ScriptModule.Publish {
            override lazy val millDiscover = Discover[this.type]
          }
        case "scala" =>
          println("B")
          new ScriptModule.Scala(millFile) with ScriptModule.Publish {
            override lazy val millDiscover = Discover[this.type]
          }
        case "kt" =>
          println("C")
          new ScriptModule.Kotlin(millFile) with ScriptModule.Publish {
            override lazy val millDiscover = Discover[this.type]
          }
      }
      case Some(targetName) =>
        val targetPath = millFile / os.up / targetName
        import mill.javalib.TestModule.*
        millFile.ext match {
          case "java" =>
            println("D")

            val targetYamlHeader = mill.constants.Util.readBuildHeader(targetPath.toNIO, targetPath.last, true)
            val targetModule = new ScriptModule.Java(targetPath) with ScriptModule.Publish {
              override lazy val millDiscover = Discover[this.type]
            }
            val testModule = testTrait match {
              case Some("TestNG") => new ScriptModule.Java(millFile) with targetModule.JavaTests with TestNg {}
              case Some("Junit4") => new ScriptModule.Java(millFile) with targetModule.JavaTests with Junit4 {}
              case Some("Junit5") => new ScriptModule.Java(millFile) with targetModule.JavaTests with Junit5 {}
            }
            testModule

          case "scala" =>
            println("E")
            val targetYamlHeader = mill.constants.Util.readBuildHeader(targetPath.toNIO, targetPath.last, true)
            val targetModule = new ScriptModule.Scala(targetPath) with ScriptModule.Publish {
              override lazy val millDiscover = Discover[this.type]
            }
            val testModule = testTrait match {
              case Some("TestNG") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with TestNg {}
              case Some("Junit4") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Junit4 {}
              case Some("Junit5") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Junit5 {}
              case Some("Scalatest") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ScalaTest {}
              case Some("Specs2") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Specs2 {}
              case Some("Utest") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Utest {}
              case Some("Munit") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Munit {}
              case Some("Weaver") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Weaver {}
              case Some("ZioTest") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ZioTest {}
              case Some("ScalaCheck") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ScalaCheck {}
            }
            testModule

          case "kt" =>
            println("F")
            val targetYamlHeader = mill.constants.Util.readBuildHeader(targetPath.toNIO, targetPath.last, true)
            val targetModule = new ScriptModule.Kotlin(targetPath) with ScriptModule.Publish {
              override lazy val millDiscover = Discover[this.type]
            }
            val testModule = testTrait match {
              case Some("TestNG") => new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with TestNg {}
              case Some("Junit4") => new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with Junit4 {}
              case Some("Junit5") => new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with Junit5 {}
            }
            testModule
        }
    }
    (bootstrapModule, yamlHeader)
  }
}
