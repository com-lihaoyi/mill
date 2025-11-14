package millbuild

import mill.javalib.*

object Deps {

  val akkaStream = mvn"com.typesafe.akka::akka-stream:2.5.6"
  val config = mvn"com.typesafe:config:1.3.1"
  val jclOverSlf4j = mvn"org.slf4j:jcl-over-slf4j:1.7.25"
  val logbackClassic = mvn"ch.qos.logback:logback-classic:1.2.3"
  val logstashLogbackEncoder =
    mvn"net.logstash.logback:logstash-logback-encoder:4.11"
  val monocleCore = mvn"com.github.julien-truffaut::monocle-core:1.4.0"
  val monocleMacro = mvn"com.github.julien-truffaut::monocle-macro:1.4.0"
  val nettyTransportNativeEpoll =
    mvn"io.netty:netty-transport-native-epoll:4.1.118.Final;classifier=linux-x86_64;type=pom;exclude=io.netty:netty-transport-native-epoll"
  val pureconfig = mvn"com.github.pureconfig::pureconfig:0.8.0"
  val scalaLogging = mvn"com.typesafe.scala-logging::scala-logging:3.7.2"
  val scalacheck = mvn"org.scalacheck::scalacheck:1.13.5"
  val scalatest = mvn"org.scalatest::scalatest:3.0.4"
  val wartremover = mvn"org.wartremover::wartremover:2.2.1"
}
