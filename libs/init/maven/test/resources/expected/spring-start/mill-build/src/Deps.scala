package millbuild

import mill.javalib._

object Deps {

  val springBootStarterDataJdbc =
    mvn"org.springframework.boot:spring-boot-starter-data-jdbc:3.5.6"
}
