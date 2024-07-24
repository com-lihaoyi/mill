import mill._, javalib._, publish._

object hello extends RootModule with MavenModule {
  def micronautVersion = "4.5.3"
  def ivyDeps = Agg(
    ivy"io.micronaut:micronaut-http-server-netty:$micronautVersion",
    ivy"io.micronaut.serde:micronaut-serde-jackson:2.10.1",
    ivy"ch.qos.logback:logback-classic:1.5.3",
  )

  def javacOptions = Seq(
    "-source", "17",
    "-target", "17",
    "-processorpath",
    http.runClasspath().map(_.path).mkString(":"),
    "-processorpath",
    serde.runClasspath().map(_.path).mkString(":"),
    "-Amicronaut.processing.group=example.micronaut",
    "-Amicronaut.processing.module=default"
  )

  override def mainClass = Some("example.micronaut.Application")

  object test extends MavenTests with TestModule.Junit4{
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"io.micronaut:micronaut-http-client:$micronautVersion",
      ivy"io.micronaut.test:micronaut-test-junit5:$micronautVersion",
      ivy"org.junit.jupiter:junit-jupiter-api:5.8.1",
      ivy"org.junit.jupiter:junit-jupiter-engine:5.8.1"
    )
  }

  def compileIvyDeps = Agg(
    ivy"io.micronaut:micronaut-http-validation:$micronautVersion",

  )
  object http extends JavaModule{
    def ivyDeps = Agg(ivy"io.micronaut:micronaut-http-validation:$micronautVersion")
  }
  object serde extends JavaModule{
    def ivyDeps = Agg(ivy"io.micronaut.serde:micronaut-serde-processor:2.10.1")
  }

}


// This example demonstrates how to set up a simple Micronaut example service,
// able to handle a single HTTP request at `/` and reply with a single response.


/** Usage

> mill test
...HelloJettyTest.testHelloJetty finished...

> mill runBackground

> curl http://localhost:8085
...<h1>Hello, World!</h1>...

> mill clean runBackground

*/
