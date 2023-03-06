package mill.scalajslib.worker.jsenv

import mill.scalajslib.worker.api._

object Selenium {
  def apply(config: JsEnvConfig.Selenium) =
    new org.scalajs.jsenv.selenium.SeleniumJSEnv(
      capabilities = config.capabilities match {
        case options: JsEnvConfig.Selenium.ChromeOptions =>
          val result = new org.openqa.selenium.chrome.ChromeOptions()
          result.setHeadless(options.headless)
          result
        case options: JsEnvConfig.Selenium.FirefoxOptions =>
          val result = new org.openqa.selenium.firefox.FirefoxOptions()
          result.setHeadless(options.headless)
          result
        case options: JsEnvConfig.Selenium.SafariOptions =>
          val result = new org.openqa.selenium.safari.SafariOptions()
          result
      }
    )
}
