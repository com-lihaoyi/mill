package millbuild

import mill.javalib.*

object Deps {

  val osLib = mvn"com.lihaoyi::os-lib:0.11.5"
  val upickle = mvn"com.lihaoyi::upickle::4.3.0"
  val utest = mvn"com.lihaoyi::utest::0.9.1"
}
