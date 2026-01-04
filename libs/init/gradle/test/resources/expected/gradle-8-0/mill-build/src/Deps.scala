package millbuild
import mill.javalib.*
object Deps {

  val commonsText = mvn"org.apache.commons:commons-text:1.9"
  val error_prone_core = mvn"com.google.errorprone:error_prone_core:2.28.0"
  val junitBom = mvn"org.junit:junit-bom:5.9.1"
  val junitJupiter = mvn"org.junit.jupiter:junit-jupiter:5.9.1"
}
