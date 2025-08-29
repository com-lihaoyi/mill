package mill.scalajslib.worker.api

private[scalajslib] sealed trait JsEnvConfig

private[scalajslib] object JsEnvConfig {
  final case class NodeJs(
      executable: String,
      args: List[String],
      env: Map[String, String],
      sourceMap: Boolean
  ) extends JsEnvConfig

  final case class JsDom(
      executable: String,
      args: List[String],
      env: Map[String, String]
  ) extends JsEnvConfig

  final case class ExoegoJsDomNodeJs(
      executable: String,
      args: List[String],
      env: Map[String, String]
  ) extends JsEnvConfig

  final case class Phantom(
      executable: String,
      args: List[String],
      env: Map[String, String],
      autoExit: Boolean
  ) extends JsEnvConfig

  final case class Selenium(
      capabilities: Selenium.Capabilities
  ) extends JsEnvConfig
  object Selenium {
    sealed trait Capabilities
    case class ChromeOptions(headless: Boolean) extends Capabilities
    case class FirefoxOptions(headless: Boolean) extends Capabilities
    case class SafariOptions() extends Capabilities
  }

  final case class Playwright(
      browserName: String = "chromium",
      headless: Boolean = true,
      showLogs: Boolean = false,
      debug: Boolean = false,
      //   pwConfig: Config = Config(),
      runConfigEnv: Map[String, String] = Map.empty,
      launchOptions: List[String] = Nil,
      additionalLaunchOptions: List[String] = Nil
  ) extends JsEnvConfig
}
