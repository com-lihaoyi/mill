import mill._, javalib._

object hello extends RootModule with JavaModule {
  def ivyDeps = Agg(
    ivy"org.springframework.boot:spring-boot-starter-web:2.5.6",
    ivy"org.springframework.boot:spring-boot-starter-actuator:2.5.6"
  )

  object test extends JavaTests with TestModule.Junit5 {
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.springframework.boot:spring-boot-starter-test:2.5.6"
    )
  }
}

// This example demonstrates how to set up a simple Spring Boot webserver,
// able to handle a single HTTP request at `/` and reply with a single response.


/** Usage

> mill test
...com.example.HelloSpringBootTest#shouldReturnDefaultMessage() finished...

> mill runBackground; sleep 2 # give time for server to start

> curl http://localhost:8086
...<h1>Hello, World!</h1>...

> mill clean runBackground

*/
