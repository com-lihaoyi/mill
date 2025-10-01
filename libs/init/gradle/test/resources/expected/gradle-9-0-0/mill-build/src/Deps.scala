package millbuild

import mill.javalib._

object Deps {

  val commonsText = mvn"org.apache.commons:commons-text:1.13.0"
  val junitJupiter = mvn"org.junit.jupiter:junit-jupiter:5.12.1"
  val junitPlatformLauncher = mvn"org.junit.platform:junit-platform-launcher"
}
