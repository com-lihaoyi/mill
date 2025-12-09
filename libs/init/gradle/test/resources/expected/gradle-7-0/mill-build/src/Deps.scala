package millbuild
import mill.javalib.*
object Deps {

  val commonsText = mvn"org.apache.commons:commons-text:1.9"
  val junitBom = mvn"org.junit:junit-bom:5.7.1"
  val junitJupiterApi = mvn"org.junit.jupiter:junit-jupiter-api:5.7.1"
  val jupiterInterface = mvn"com.github.sbt.junit:jupiter-interface:0.11.4"
}
