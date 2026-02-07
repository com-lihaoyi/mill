package millbuild
import mill._, scalalib.*

/**
 * Publishable module which contains strictly handled API.
 * Those modules are also included in the generated API documentation.
 */
trait MillStableScalaModule extends MillPublishScalaModule with MillStableJavaModule
