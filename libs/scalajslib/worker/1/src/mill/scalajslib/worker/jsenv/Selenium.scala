package mill.scalajslib.worker.jsenv

import mill.scalajslib.worker.api._
import scala.util.chaining.given

object Selenium {
  def apply(config: JsEnvConfig.Selenium) =
    new org.scalajs.jsenv.selenium.SeleniumJSEnv(
      capabilities = config.capabilities match {

        case options: JsEnvConfig.Selenium.ChromeOptions =>
          new org.openqa.selenium.chrome.ChromeOptions()
            .tap(_.setHeadless(options.headless))

        case options: JsEnvConfig.Selenium.FirefoxOptions =>
          new org.openqa.selenium.firefox.FirefoxOptions()
            .tap(_.setHeadless(options.headless))

        case _: JsEnvConfig.Selenium.SafariOptions =>
          new org.openqa.selenium.safari.SafariOptions()
      }
    )
}
