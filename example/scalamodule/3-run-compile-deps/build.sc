// If you want to use additional dependencies at runtime or override
// dependencies and their versions at runtime, you can do so with
// `runIvyDeps`.

import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"
  def moduleDeps = Seq(bar)
  def runIvyDeps = Agg(
    ivy"javax.servlet:servlet-api:2.5",
    ivy"org.eclipse.jetty:jetty-server:9.4.42.v20210604",
    ivy"org.eclipse.jetty:jetty-servlet:9.4.42.v20210604"
  )
  def mainClass = Some("bar.Bar")
}

// You can also declare compile-time-only dependencies with `compileIvyDeps`.
// These are present in the compile classpath, but will not propagated to the
// transitive dependencies.

object bar extends ScalaModule {
  def scalaVersion = "2.13.8"
  def compileIvyDeps = Agg(
    ivy"javax.servlet:servlet-api:2.5",
    ivy"org.eclipse.jetty:jetty-server:9.4.42.v20210604",
    ivy"org.eclipse.jetty:jetty-servlet:9.4.42.v20210604"
  )
}

// Typically, Mill assumes that a module with compile-time dependencies will
// only be run after someone includes the equivalent run-time dependencies in
// a later build step. e.g. in the case above, `bar` defines the compile-time
// dependencies, and `foo` then depends on `bar` and includes the runtime
// dependencies. That is why we can run `foo` as show below:

/** Usage

> ./mill foo.runBackground

> curl http://localhost:8080
<html><body>Hello World!</body></html>

*/


// NOTE: Compile-time dependencies are translated to `provided`-scoped
// dependencies when publish to Maven or Ivy-Repositories.
//
// === Keeping up-to-date with Scala Steward
//
// It's always a good idea to keep your dependencies up-to-date.
//
// If your project is hosted on GitHub, GitLab, or Bitbucket, you can use
// https://github.com/scala-steward-org/scala-steward[Scala Steward] to
// automatically open a pull request to update your dependencies whenever
// there is a newer version available.
//
// TIP: Scala Steward can also keep your
// xref:Installation.adoc#_automatic_mill_updates[Mill version up-to-date].


