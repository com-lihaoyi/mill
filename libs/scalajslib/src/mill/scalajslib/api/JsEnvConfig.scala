package mill.scalajslib.api

import upickle.{ReadWriter => RW, macroRW}
import mill.api.internal.Mirrors.autoMirror
import mill.api.internal.Mirrors

sealed trait JsEnvConfig
object JsEnvConfig {
  implicit def rwNodeJs: RW[NodeJs] = macroRW
  implicit def rwJsDom: RW[JsDom] = macroRW
  implicit def rwExoegoJsDomNodeJs: RW[ExoegoJsDomNodeJs] = macroRW
  implicit def rwPhantom: RW[Phantom] = macroRW
  implicit def rwSelenium: RW[Selenium] = macroRW
  implicit def rwPlaywright: RW[Playwright] = macroRW
  implicit def rw: RW[JsEnvConfig] = macroRW

  private given Root_JsEnvConfig: Mirrors.Root[JsEnvConfig] =
    Mirrors.autoRoot[JsEnvConfig]

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

    private given Root_Capabilities: Mirrors.Root[Capabilities] =
      Mirrors.autoRoot[Capabilities]

    def apply(capabilities: Capabilities): Selenium =
      Selenium(capabilities = capabilities)

    sealed trait Capabilities
    class ChromeOptions private (val headless: Boolean) extends Capabilities {
      def withHeadless(value: Boolean): Unit = copy(headless = value)
      private def copy(
          headless: Boolean
      ): ChromeOptions = ChromeOptions(
        headless = headless
      )
    }
    object ChromeOptions {
      implicit def rw: RW[ChromeOptions] = macroRW

      def apply(headless: Boolean): ChromeOptions =
        ChromeOptions(headless = headless)
    }
    class FirefoxOptions private (val headless: Boolean) extends Capabilities {
      def withHeadless(value: Boolean): Unit = copy(headless = value)
      private def copy(
          headless: Boolean
      ): FirefoxOptions = FirefoxOptions(
        headless = headless
      )
    }
    object FirefoxOptions {
      implicit def rw: RW[FirefoxOptions] = macroRW

      def apply(): FirefoxOptions =
        FirefoxOptions(headless = false)
      def apply(headless: Boolean): FirefoxOptions =
        FirefoxOptions(headless = headless)
    }

    class SafariOptions private () extends Capabilities
    object SafariOptions {
      implicit def rw: RW[SafariOptions] = macroRW

      def apply(): SafariOptions =
        SafariOptions()
    }
  }

  final class Playwright private (val capabilities: Playwright.Capabilities) extends JsEnvConfig
  object Playwright {
    implicit def rwCapabilities: RW[Capabilities] = macroRW

    private given Root_Capabilities: Mirrors.Root[Capabilities] =
      Mirrors.autoRoot[Capabilities]

    def apply(capabilities: Capabilities): Playwright =
      Playwright(capabilities = capabilities)

    sealed trait Capabilities

    /**
     * Default launch options for Chrome, directly derived from the scala-js-env-playwright implementation: https://github.com/ThijsBroersen/scala-js-env-playwright/blob/main/src/main/scala/jsenv/playwright/PlaywrightJSEnv.scala
     */
    val defaultChromeLaunchOptions = List(
      "--disable-extensions",
      "--disable-web-security",
      "--allow-running-insecure-content",
      "--disable-site-isolation-trials",
      "--allow-file-access-from-files",
      "--disable-gpu"
    )

    def chrome(
        headless: Boolean = true,
        showLogs: Boolean = false,
        debug: Boolean = false,
        launchOptions: List[String] = defaultChromeLaunchOptions
    ): Playwright =
      val options = ChromeOptions(
        headless = headless,
        showLogs = showLogs,
        debug = debug,
        launchOptions = launchOptions
      )
      Playwright(options)

    case class ChromeOptions(
        headless: Boolean = true,
        showLogs: Boolean = false,
        debug: Boolean = false,
        launchOptions: List[String] = defaultChromeLaunchOptions
    ) extends Capabilities {
      def withHeadless(value: Boolean): ChromeOptions = copy(headless = value)
      def withShowLogs(value: Boolean): ChromeOptions = copy(showLogs = value)
      def withDebug(value: Boolean): ChromeOptions = copy(debug = value)
      def withLaunchOptions(value: List[String]): ChromeOptions = copy(launchOptions = value)
    }
    object ChromeOptions:
      implicit def rw: RW[ChromeOptions] = macroRW

    /**
     * Default Firefox user prefs, directly derived from the scala-js-env-playwright implementation: https://github.com/ThijsBroersen/scala-js-env-playwright/blob/main/src/main/scala/jsenv/playwright/PlaywrightJSEnv.scala
     */
    val defaultFirefoxUserPrefs: Map[String, String | Double | Boolean] =
      Map(
        "security.mixed_content.block_active_content" -> false,
        "security.mixed_content.upgrade_display_content" -> false,
        "security.file_uri.strict_origin_policy" -> false
      )

    def firefox(
        headless: Boolean = true,
        showLogs: Boolean = false,
        debug: Boolean = false,
        firefoxUserPrefs: Map[String, String | Double | Boolean] = defaultFirefoxUserPrefs
    ): Playwright =
      val options = FirefoxOptions(
        headless = headless,
        showLogs = showLogs,
        debug = debug,
        firefoxUserPrefs = firefoxUserPrefs
      )
      Playwright(options)
    case class FirefoxOptions(
        headless: Boolean = true,
        showLogs: Boolean = false,
        debug: Boolean = false,
        firefoxUserPrefs: Map[String, String | Double | Boolean] = defaultFirefoxUserPrefs
    ) extends Capabilities {
      def withHeadless(value: Boolean): FirefoxOptions = copy(headless = value)
      def withShowLogs(value: Boolean): FirefoxOptions = copy(showLogs = value)
      def withDebug(value: Boolean): FirefoxOptions = copy(debug = value)
      def withFirefoxUserPrefs(value: Map[String, String | Double | Boolean]): FirefoxOptions =
        copy(firefoxUserPrefs = value)
    }
    object FirefoxOptions:
      given upickle.default.ReadWriter[String | Double | Boolean] =
        upickle.default.readwriter[ujson.Value].bimap[String | Double | Boolean](
          {
            case v: Boolean => upickle.default.writeJs(v)
            case v: Double => upickle.default.writeJs(v)
            case v: String => upickle.default.writeJs(v)
          },
          json =>
            json.boolOpt
              .orElse(
                json.numOpt
              ).orElse(
                json.strOpt.map(_.toString)
              ).getOrElse(throw Exception("Invalid value"))
        )
      given rw: RW[FirefoxOptions] = macroRW

    /**
     * Default launch options for Webkit, directly derived from the scala-js-env-playwright implementation: https://github.com/ThijsBroersen/scala-js-env-playwright/blob/main/src/main/scala/jsenv/playwright/PlaywrightJSEnv.scala
     */
    val defaultWebkitLaunchOptions = List(
      "--disable-extensions",
      "--disable-web-security",
      "--allow-running-insecure-content",
      "--disable-site-isolation-trials",
      "--allow-file-access-from-files"
    )

    def webkit(
        headless: Boolean = true,
        showLogs: Boolean = false,
        debug: Boolean = false,
        launchOptions: List[String] = defaultWebkitLaunchOptions
    ): Playwright =
      val options = WebkitOptions(
        headless = headless,
        showLogs = showLogs,
        debug = debug,
        launchOptions = launchOptions
      )
      Playwright(options)

    case class WebkitOptions(
        headless: Boolean = true,
        showLogs: Boolean = false,
        debug: Boolean = false,
        launchOptions: List[String] = defaultWebkitLaunchOptions
    ) extends Capabilities {
      def withHeadless(value: Boolean): WebkitOptions = copy(headless = value)
      def withShowLogs(value: Boolean): WebkitOptions = copy(showLogs = value)
      def withDebug(value: Boolean): WebkitOptions = copy(debug = value)
      def withLaunchOptions(value: List[String]): WebkitOptions = copy(launchOptions = value)
    }
    object WebkitOptions:
      implicit def rw: RW[WebkitOptions] = macroRW
  }
}
