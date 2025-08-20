package millbuild

import mill.javalib._

object Deps {

  val commonsMath3 = mvn"org.apache.commons:commons-math3:3.6.1"
  val guava = mvn"com.google.guava:guava:33.2.1-jre"
  val junitJupiter = mvn"org.junit.jupiter:junit-jupiter:5.10.3"
  val junitPlatformLauncher = mvn"org.junit.platform:junit-platform-launcher"
}
