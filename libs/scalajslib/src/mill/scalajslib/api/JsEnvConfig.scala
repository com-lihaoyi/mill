package mill.scalajslib.api

import upickle.default.{ReadWriter => RW, macroRW}
import mill.api.internal.Mirrors.autoMirror
import mill.api.internal.Mirrors

sealed trait JsEnvConfig
object JsEnvConfig {
  implicit def rwNodeJs: RW[NodeJs] = macroRW
  implicit def rwJsDom: RW[JsDom] = macroRW
  implicit def rwExoegoJsDomNodeJs: RW[ExoegoJsDomNodeJs] = macroRW
  implicit def rwPhantom: RW[Phantom] = macroRW
  implicit def rwSelenium: RW[Selenium] = macroRW
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
