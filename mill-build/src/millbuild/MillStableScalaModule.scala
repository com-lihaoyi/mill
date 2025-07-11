package millbuild
import mill._, scalalib._

/** Publishable module which contains strictly handled API. */
trait MillStableScalaModule extends MillPublishScalaModule with MillStableJavaModule
