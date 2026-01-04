package millbuild
import mill.javalib.*
object Deps {

  val guava = mvn"com.google.guava:guava:28.0-jre"
  val testng = mvn"org.testng:testng:6.14.3"
}
