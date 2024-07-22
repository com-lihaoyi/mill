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
    ivy"org.webjars.npm:todomvc-app-css:2.4.1",

    ivy"com.h2database:h2:2.3.230"
  )

  object test extends JavaModuleTests with TestModule.Junit5 {
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.springframework.boot:spring-boot-starter-test:2.5.6"
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


/** Usage

> mill test
...com.example.TodomvcApplicationTests#homePageLoads() finished...
...com.example.TodomvcApplicationTests#addNewTodoItem() finished...

> mill runBackground

> curl http://localhost:8087
...<h1>todos</h1>...

> mill clean runBackground

*/
