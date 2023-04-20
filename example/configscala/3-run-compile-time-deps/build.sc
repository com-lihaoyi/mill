// == Runtime and Compile-time Dependencies
//
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


/** Usage

> ./mill foo.runBackground

> curl http://localhost:8080
<html><body>Hello World!</body></html>

*/

// [IMPORTANT]
// --
// _Mill has no `test`-scoped dependencies!_
//
// You might be used to test-scoped dependencies from other build tools like
// Maven, Gradle or sbt. As test modules in Mill are just regular modules,
// there is no special need for a dedicated test-scope. You can use `ivyDeps`
// and `runIvyDeps` to declare dependencies in test modules.
// --
//
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

