package millbuild
import mill.javalib.*
object Deps {

  val hamcrestCore = mvn"org.hamcrest:hamcrest-core:1.2.1"
  val hamcrestLibrary = mvn"org.hamcrest:hamcrest-library:1.2.1"
  val jspApi = mvn"javax.servlet.jsp:jsp-api:2.2"
  val junitDep = mvn"junit:junit-dep:4.10"
  val mockitoCore = mvn"org.mockito:mockito-core:1.8.5"
  val servletApi = mvn"javax.servlet:servlet-api:2.5"
}
