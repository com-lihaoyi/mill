
//// SNIPPET:BUILD3

import mill._, scalalib._

object qux extends ScalaModule {
  def scalaVersion = "2.13.8"

  object test extends ScalaTests with TestModule.Utest {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.8.4")
  }
  object integration extends ScalaTests with TestModule.Utest {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.8.4")
  }
}
//// SNIPPET:END

// You can also define test suites with different names other than `test`. For example,
// the build below defines a test suite with the name `integration`, in addition
// to that named `test`.

// These two test modules will expect their sources to be in their respective `foo/test` and
// `foo/integration` folder respectively


/** Usage

> mill qux.test # run the normal test suite
...qux.QuxTests...hello...
...qux.QuxTests...world...


> mill qux.integration  # run the integration test suite
...qux.QuxIntegrationTests...helloworld...

> mill qux.integration.testCached # run the normal test suite, caching the results
...qux.QuxIntegrationTests...helloworld...

> mill qux.{test,integration} # run both test suites
...qux.QuxTests...hello...
...qux.QuxTests...world...
...qux.QuxIntegrationTests...helloworld...

> mill __.integration.testCached # run all integration test suites

*/