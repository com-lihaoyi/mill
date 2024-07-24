import mill._, javalib._

object hello extends RootModule with MicronautModule {
  def micronautVersion = "4.5.3"
  def ivyDeps = Agg(
    ivy"io.micronaut:micronaut-http-server-netty:$micronautVersion",
    ivy"io.micronaut.serde:micronaut-serde-jackson:2.10.1",
    ivy"ch.qos.logback:logback-classic:1.5.3",
  )


  object test extends MavenTests with TestModule.Junit5{
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"io.micronaut:micronaut-http-client:$micronautVersion",
      ivy"io.micronaut.test:micronaut-test-junit5:4.4.0",
      ivy"org.junit.jupiter:junit-jupiter-api:5.8.1",
      ivy"org.junit.jupiter:junit-jupiter-engine:5.8.1"
    )
  }
}

trait MicronautModule extends MavenModule{
  def micronautVersion: String

  object processors extends JavaModule{
    def ivyDeps = Agg(
      ivy"io.micronaut:micronaut-http-validation:$micronautVersion",
      ivy"io.micronaut.serde:micronaut-serde-processor:2.10.1",
      ivy"io.micronaut:micronaut-inject-java:$micronautVersion"
    )
  }

  def javacOptions = Seq(
    "-processorpath",
    processors.runClasspath().map(_.path).mkString(":"),
    "-processor",
    "io.micronaut.annotation.processing.TypeElementVisitorProcessor",
    "-processor",
    "io.micronaut.annotation.processing.PackageConfigurationInjectProcessor",
    "-processor",
    "io.micronaut.annotation.processing.BeanDefinitionInjectProcessor",
    "-Amicronaut.processing.group=example.micronaut",
    "-Amicronaut.processing.module=default"
  )
}


// This example demonstrates how to set up a simple Micronaut example service,
// using the code from the
// https://guides.micronaut.io/latest/creating-your-first-micronaut-app.html[Micronaut Tutorial].
//
// To preserve compatibility with the file layout from the example project, we use `MavenModule`,
// which follows the `src/main/java` and `src/test/java` folder convention.
//
// Although Mill does not have a built in `MicronautModule`, this example shows how easy
// it is to define it yourself as `trait MicronautModule`: setting up the annotation processor
// classpath as a `JavaModule` and setting up the annotation via `javacOptions`. Once defined,
// you can then use `MicronautModule in your build just like you.
//
// The `MicronautModule` shown here does not implement the full functionality of the micronaut
// CLI, but it easily can be extended with more features as necessary.


/** Usage

> mill test
...example.micronaut.HelloControllerTest#testHello()...

> mill runBackground

> curl http://localhost:8087/hello
...Hello World...

> mill clean runBackground

*/
