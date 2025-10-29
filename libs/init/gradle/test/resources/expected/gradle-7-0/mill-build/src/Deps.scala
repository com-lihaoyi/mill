package millbuild

import mill.javalib.*

object Deps {

  val commonsText = mvn"org.apache.commons:commons-text:1.9"
  val junitJupiterApi = mvn"org.junit.jupiter:junit-jupiter-api:5.7.1"
  val junitJupiterEngine = mvn"org.junit.jupiter:junit-jupiter-engine"
}
