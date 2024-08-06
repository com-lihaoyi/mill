import mill._, javalib._

object hello extends RootModule with JavaModule {
  def ivyDeps = Agg(
    ivy"org.springframework.boot:spring-boot-starter-data-jpa:2.5.4",
    ivy"org.springframework.boot:spring-boot-starter-thymeleaf:2.5.4",
    ivy"org.springframework.boot:spring-boot-starter-validation:2.5.4",
    ivy"org.springframework.boot:spring-boot-starter-web:2.5.4",

    ivy"javax.xml.bind:jaxb-api:2.3.1",

    ivy"org.webjars:webjars-locator:0.41",
    ivy"org.webjars.npm:todomvc-common:1.0.5",
    ivy"org.webjars.npm:todomvc-app-css:2.4.1",


  )

  trait HelloTests extends JavaTests with TestModule.Junit5{
    def mainClass = Some("com.example.TodomvcApplication")
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.springframework.boot:spring-boot-starter-test:2.5.6"
    )
  }

  object test extends HelloTests{
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"com.h2database:h2:2.3.230",
    )
  }

  object integration extends HelloTests {
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.testcontainers:testcontainers:1.18.0",
      ivy"org.testcontainers:junit-jupiter:1.18.0",
      ivy"org.testcontainers:postgresql:1.18.0",
      ivy"org.postgresql:postgresql:42.6.0",
    )
  }
}

// This is a larger example using Spring Boot, implementing the well known
// https://todomvc.com/[TodoMVC] example app. Apart from running a webserver,
// this example also demonstrates:
//
// * Serving HTML templates using Thymeleaf
// * Serving static Javascript and CSS using Webjars
// * Querying a SQL database using JPA and H2
// * Unit testing using a H2 in-memory database
// * Integration testing using Testcontainers Postgres in Docker


/** Usage

> mill test
...com.example.TodomvcTests#homePageLoads() finished...
...com.example.TodomvcTests#addNewTodoItem() finished...

> mill integration
...com.example.TodomvcIntegrationTests#homePageLoads() finished...
...com.example.TodomvcIntegrationTests#addNewTodoItem() finished...

> mill test.runBackground

> curl http://localhost:8087
...<h1>todos</h1>...

> mill clean runBackground

*/
