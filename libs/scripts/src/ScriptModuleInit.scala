package mill.scripts
import mill.*
import mill.api.Discover
object ScriptModuleInit extends ((String, Map[String, String]) => mill.api.ExternalModule){
  def apply(millFileString: String, env: Map[String, String]) = {
    val workspace = mill.api.BuildCtx.workspaceRoot
    val millFile = os.Path(millFileString, workspace)
    val headerData = mill.constants.Util.readBuildHeader(millFile.toNIO, millFile.last, true)
    lazy val parsedHeaderData: Map[String, ujson.Value] = {
      import org.snakeyaml.engine.v2.api.{Load, LoadSettings}
      val loaded = new Load(LoadSettings.builder().build()).loadFromString(headerData)
      // recursively convert java data structure to ujson.Value
      val envWithPwd = env ++ Seq(
        "PWD" -> workspace.toString,
        "PWD_URI" -> workspace.toURI.toString,
        "MILL_VERSION" -> mill.constants.BuildInfo.millVersion,
        "MILL_BIN_PLATFORM" -> mill.constants.BuildInfo.millBinPlatform
      )

      def rec(x: Any): ujson.Value = {
        import scala.jdk.CollectionConverters._
        x match {
          case d: java.util.Date => ujson.Str(d.toString)
          case s: String => ujson.Str(mill.constants.Util.interpolateEnvVars(s, envWithPwd.asJava))
          case d: Double => ujson.Num(d)
          case d: Int => ujson.Num(d)
          case d: Long => ujson.Num(d)
          case true => ujson.True
          case false => ujson.False
          case null => ujson.Null
          case m: java.util.Map[Object, Object] =>
            val scalaMap = m.asScala
            ujson.Obj.from(scalaMap.map { case (k, v) => (k.toString, rec(v)) })
          case l: java.util.List[Object] =>
            val scalaList: collection.Seq[Object] = l.asScala
            ujson.Arr.from(scalaList.map(rec))
        }
      }

      rec(loaded).objOpt.getOrElse(Map.empty[String, ujson.Value]).toMap
    }

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
            val targetYamlHeader = mill.constants.Util.readBuildHeader(targetPath.toNIO, targetPath.last, true)
            val targetModule = new ScriptModule.Java(targetPath) with ScriptModule.Publish {
              override lazy val millDiscover = Discover[this.type]
              override def buildOverrides = parsedHeaderData
            }
            val testModule = testTrait match {
              case Some("TestNG") => new ScriptModule.Java(millFile) with targetModule.JavaTests with TestNg {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Junit4") => new ScriptModule.Java(millFile) with targetModule.JavaTests with Junit4 {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Junit5") => new ScriptModule.Java(millFile) with targetModule.JavaTests with Junit5 {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
            }
            testModule

          case "scala" =>
            val targetYamlHeader = mill.constants.Util.readBuildHeader(targetPath.toNIO, targetPath.last, true)
            val targetModule = new ScriptModule.Scala(targetPath) with ScriptModule.Publish {
              override lazy val millDiscover = Discover[this.type]
              override def buildOverrides = parsedHeaderData
            }
            val testModule = testTrait match {
              case Some("TestNG") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with TestNg {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Junit4") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Junit4 {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Junit5") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Junit5 {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Scalatest") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ScalaTest {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Specs2") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Specs2 {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Utest") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Utest {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Munit") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Munit {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Weaver") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with Weaver {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("ZioTest") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ZioTest {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("ScalaCheck") => new ScriptModule.Scala(millFile) with targetModule.ScalaTests with ScalaCheck {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
            }
            testModule

          case "kt" =>
            val targetYamlHeader = mill.constants.Util.readBuildHeader(targetPath.toNIO, targetPath.last, true)
            val targetModule = new ScriptModule.Kotlin(targetPath) with ScriptModule.Publish {
              override lazy val millDiscover = Discover[this.type]
            }
            val testModule = testTrait match {
              case Some("TestNG") => new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with TestNg {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Junit4") => new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with Junit4 {
                override lazy val millDiscover = Discover[this.type]
                override def buildOverrides = parsedHeaderData
              }
              case Some("Junit5") => new ScriptModule.Kotlin(millFile) with targetModule.KotlinTests with Junit5 {
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
