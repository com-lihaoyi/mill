package millbuild
import mill.javalib.*
object Deps {

  val junitBom = mvn"org.junit:junit-bom:5.11.0"
  val junitJupiterApi = mvn"org.junit.jupiter:junit-jupiter-api:5.11.0"
  val junitJupiterParams = mvn"org.junit.jupiter:junit-jupiter-params:5.11.0"
}
