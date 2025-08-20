package millbuild

import mill.javalib._

object Deps {

  val commonsText = mvn"org.apache.commons:commons-text"
  val junitJupiter = mvn"org.junit.jupiter:junit-jupiter:5.10.3"
  val junitPlatformLauncher = mvn"org.junit.platform:junit-platform-launcher"
}
