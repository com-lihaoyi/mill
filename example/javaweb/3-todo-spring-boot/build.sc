import mill._, javalib._, publish._

object hello extends RootModule with JavaModule {
  def ivyDeps = Agg(
    ivy"org.springframework.boot:spring-boot-starter-data-jpa:2.5.4",
    ivy"org.springframework.boot:spring-boot-starter-thymeleaf:2.5.4",
    ivy"org.springframework.boot:spring-boot-starter-validation:2.5.4",
    ivy"org.springframework.boot:spring-boot-starter-web:2.5.4",

    ivy"javax.xml.bind:jaxb-api:2.3.1",
    ivy"org.webjars:webjars-locator:0.41",
    ivy"org.webjars.npm:todomvc-common:1.0.5",
    ivy"org.webjars.npm:todomvc-app-css:2.4.1"
  )
  def runIvyDeps = Agg(
    ivy"com.h2database:h2:2.3.230"
  )

  object test extends JavaModuleTests with TestModule.Junit5 {
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.springframework.boot:spring-boot-starter-test:2.5.6"
    )
    def runIvyDeps = Agg(
      ivy"com.h2database:h2:2.3.230"
    )
  }
}

// This example demonstrates how to set up a simple Spring Boot webserver,
// able to handle a single HTTP request at `/` and reply with a single response.


/** Usage

> mill test
...com.example.TodomvcApplicationTests#homePageLoads() finished...
...com.example.TodomvcApplicationTests#addNewTodoItem() finished...

> mill runBackground

> curl http://localhost:8080
...<h1>todos</h1>...

> mill clean runBackground

*/
