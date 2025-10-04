package millbuild

import mill.javalib._

object Deps {

  val commonsText = mvn"org.apache.commons:commons-text:1.9"
  val junitJupiter = mvn"org.junit.jupiter:junit-jupiter:5.9.1"
}
