package mill.scalajslib.api

import upickle.default.{ReadWriter => RW, macroRW}

sealed trait ModuleKind
object ModuleKind {
  object NoModule extends ModuleKind
  object CommonJSModule extends ModuleKind
  object ESModule extends ModuleKind

  implicit def rwNoModule: RW[NoModule.type] = macroRW
  implicit def rwCommonJSModule: RW[CommonJSModule.type] = macroRW
  implicit def rwESModule: RW[ESModule.type] = macroRW
  implicit def rw: RW[ModuleKind] = macroRW
}

sealed trait ESVersion
object ESVersion {
  object ES2015 extends ESVersion
  implicit val rw2015: RW[ES2015.type] = macroRW
  object ES2016 extends ESVersion
  implicit val rw2016: RW[ES2016.type] = macroRW
  object ES2017 extends ESVersion
  implicit val rw2017: RW[ES2017.type] = macroRW
  object ES2018 extends ESVersion
  implicit val rw2018: RW[ES2018.type] = macroRW
  object ES2019 extends ESVersion
  implicit val rw2019: RW[ES2019.type] = macroRW
  object ES2020 extends ESVersion
  implicit val rw2020: RW[ES2020.type] = macroRW
  object ES2021 extends ESVersion
  implicit val rw2021: RW[ES2021.type] = macroRW
  object ES5_1 extends ESVersion
  implicit val rw5_1: RW[ES5_1.type] = macroRW

  implicit val rw: RW[ESVersion] = macroRW[ESVersion]
}

case class ESFeatures private (
    allowBigIntsForLongs: Boolean,
    avoidClasses: Boolean,
    avoidLetsAndConsts: Boolean,
    esVersion: ESVersion
) {
  def withAllowBigIntsForLongs(allowBigIntsForLongs: Boolean): ESFeatures =
    copy(allowBigIntsForLongs = allowBigIntsForLongs)
  def withAvoidClasses(avoidClasses: Boolean): ESFeatures = copy(avoidClasses = avoidClasses)
  def withAvoidLetsAndConsts(avoidLetsAndConsts: Boolean): ESFeatures =
    copy(avoidLetsAndConsts = avoidLetsAndConsts)
  def withESVersion(esVersion: ESVersion): ESFeatures = copy(esVersion = esVersion)
}
object ESFeatures {
  val Defaults: ESFeatures = ESFeatures(
    allowBigIntsForLongs = false,
    avoidClasses = true,
    avoidLetsAndConsts = true,
    esVersion = ESVersion.ES2015
  )
  implicit val rw: RW[ESFeatures] = macroRW[ESFeatures]
}

sealed trait ModuleSplitStyle
object ModuleSplitStyle {
  case object FewestModules extends ModuleSplitStyle
  implicit val rwFewestModules: RW[FewestModules.type] = macroRW
  case object SmallestModules extends ModuleSplitStyle
  implicit val rwSmallestModules: RW[SmallestModules.type] = macroRW
  final case class SmallModulesFor(packages: List[String]) extends ModuleSplitStyle
  implicit val rwSmallModulesFor: RW[SmallModulesFor] = macroRW

  implicit val rw: RW[ModuleSplitStyle] = macroRW
}

sealed trait JsEnvConfig
object JsEnvConfig {
  implicit def rwNodeJs: RW[NodeJs] = macroRW
  implicit def rwJsDom: RW[JsDom] = macroRW
  implicit def rwExoegoJsDomNodeJs: RW[ExoegoJsDomNodeJs] = macroRW
  implicit def rwPhantom: RW[Phantom] = macroRW
  implicit def rwSelenium: RW[Selenium] = macroRW
  implicit def rw: RW[JsEnvConfig] = macroRW

  /**
   * JavaScript environment to run on Node.js
   * https://github.com/scala-js/scala-js-js-envs
   */
  final case class NodeJs(
      executable: String = "node",
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty,
      sourceMap: Boolean = true
  ) extends JsEnvConfig

  /**
   * JavaScript environment to run on Node.js with jsdom
   * Adds browser dom APIs on Node.js
   * https://github.com/scala-js/scala-js-env-jsdom-nodejs
   */
  final case class JsDom(
      executable: String = "node",
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty
  ) extends JsEnvConfig

  /**
   * JavaScript environment to run on Node.js with jsdom
   * with access to require and Node.js APIs.
   * https://github.com/exoego/scala-js-env-jsdom-nodejs
   */
  final case class ExoegoJsDomNodeJs(
      executable: String = "node",
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty
  ) extends JsEnvConfig

  /**
   * JavaScript environment to run on PhantomJS
   * https://github.com/scala-js/scala-js-env-phantomjs
   */
  final case class Phantom(
      executable: String,
      args: List[String],
      env: Map[String, String],
      autoExit: Boolean
  ) extends JsEnvConfig

  /**
   * JavaScript environment to run on a browser with Selenium
   * https://github.com/scala-js/scala-js-env-selenium
   */
  final class Selenium private (
      val capabilities: Selenium.Capabilities
  ) extends JsEnvConfig
  object Selenium {
    implicit def rwCapabilities: RW[Capabilities] = macroRW

    def apply(capabilities: Capabilities): Selenium =
      new Selenium(capabilities = capabilities)

    sealed trait Capabilities
    class ChromeOptions private (val headless: Boolean) extends Capabilities {
      def withHeadless(value: Boolean): Unit = copy(headless = value)
      private def copy(
          headless: Boolean = this.headless
      ): ChromeOptions = new ChromeOptions(
        headless = headless
      )
    }
    object ChromeOptions {
      implicit def rw: RW[ChromeOptions] = macroRW

      def apply(headless: Boolean): ChromeOptions =
        new ChromeOptions(headless = headless)
    }
    class FirefoxOptions private (val headless: Boolean) extends Capabilities {
      def withHeadless(value: Boolean): Unit = copy(headless = value)
      private def copy(
          headless: Boolean = this.headless
      ): FirefoxOptions = new FirefoxOptions(
        headless = headless
      )
    }
    object FirefoxOptions {
      implicit def rw: RW[FirefoxOptions] = macroRW

      def apply(): FirefoxOptions =
        new FirefoxOptions(headless = false)
      def apply(headless: Boolean): FirefoxOptions =
        new FirefoxOptions(headless = headless)
    }

    class SafariOptions private () extends Capabilities
    object SafariOptions {
      implicit def rw: RW[SafariOptions] = macroRW

      def apply(): SafariOptions =
        new SafariOptions()
    }
  }
}

class OutputPatterns private (
    val jsFile: String,
    val sourceMapFile: String,
    val moduleName: String,
    val jsFileURI: String,
    val sourceMapURI: String
) {

  /** Pattern for the JS file name (the file containing the module's code). */
  def withJSFile(jsFile: String): OutputPatterns =
    copy(jsFile = jsFile)

  /** Pattern for the file name of the source map file of the JS file. */
  def withSourceMapFile(sourceMapFile: String): OutputPatterns =
    copy(sourceMapFile = sourceMapFile)

  /** Pattern for the module name (the string used to import a module). */
  def withModuleName(moduleName: String): OutputPatterns =
    copy(moduleName = moduleName)

  /** Pattern for the "file" field in the source map. */
  def withJSFileURI(jsFileURI: String): OutputPatterns =
    copy(jsFileURI = jsFileURI)

  /** Pattern for the source map URI in the JS file. */
  def withSourceMapURI(sourceMapURI: String): OutputPatterns =
    copy(sourceMapURI = sourceMapURI)

  override def toString(): String = {
    s"""OutputPatterns(
       |  jsFile        = $jsFile,
       |  sourceMapFile = $sourceMapFile,
       |  moduleName    = $moduleName,
       |  jsFileURI     = $jsFileURI,
       |  sourceMapURI  = $sourceMapURI,
       |)""".stripMargin
  }

  private def copy(
      jsFile: String = jsFile,
      sourceMapFile: String = sourceMapFile,
      moduleName: String = moduleName,
      jsFileURI: String = jsFileURI,
      sourceMapURI: String = sourceMapURI
  ): OutputPatterns = {
    new OutputPatterns(jsFile, sourceMapFile, moduleName, jsFileURI, sourceMapURI)
  }
}

object OutputPatterns {

  /** Default [[OutputPatterns]]; equivalent to `fromJSFile("%s.js")`. */
  val Defaults: OutputPatterns = fromJSFile("%s.js")

  /**
   * Creates [[OutputPatterns]] from a JS file pattern.
   *
   *  Other patterns are derived from the JS file pattern as follows:
   *  - `sourceMapFile`: ".map" is appended.
   *  - `moduleName`: "./" is prepended (relative path import).
   *  - `jsFileURI`: relative URI (same as the provided pattern).
   *  - `sourceMapURI`: relative URI (same as `sourceMapFile`).
   */
  def fromJSFile(jsFile: String): OutputPatterns = {
    new OutputPatterns(
      jsFile = jsFile,
      sourceMapFile = s"$jsFile.map",
      moduleName = s"./$jsFile",
      jsFileURI = jsFile,
      sourceMapURI = s"$jsFile.map"
    )
  }

  // scalafix:off; we want to hide the generic apply method
  private def apply(
      jsFile: String,
      sourceMapFile: String,
      moduleName: String,
      jsFileURI: String,
      sourceMapURI: String
  ): OutputPatterns = new OutputPatterns(
    jsFile,
    sourceMapFile,
    moduleName,
    jsFileURI,
    sourceMapURI
  )
  // scalalfix:on

  implicit val rw: RW[OutputPatterns] = macroRW[OutputPatterns]
}
