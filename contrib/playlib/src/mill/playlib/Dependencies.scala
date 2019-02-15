package mill
package playlib

import mill.scalalib._
import mill.scalalib.api._

private [playlib] trait Dependencies extends ScalaModule with Version{
  override def ivyDeps = T{
    super.ivyDeps() ++ Agg(
    ivy"com.typesafe.play::play:${playVersion()}",
    ivy"com.typesafe.play::play-guice:${playVersion()}",
    ivy"com.typesafe.play::play-server:${playVersion()}",
    ivy"com.typesafe.play::play-logback:${playVersion()}"
  )}
}
