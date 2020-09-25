import mill._
import mill.scalalib._
//import mill.contrib.Proguard

import $ivy.`com.lihaoyi::mill-contrib-proguard:$MILL_VERSION`
import contrib.proguard._

object foo extends ScalaModule with Proguard {
  def scalaVersion = "2.13.2"
}