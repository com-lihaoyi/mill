// You can also define test suites with different names other than `test`. For example,
// the build below defines a test suite with the name `integration`, in addition
// to that named `test`.

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


// These two test modules will expect their sources to be in their respective `foo/test` and
// `foo/integration` folder respectively


/** Usage

> mill 'qux.{test,integration}' # run both test suites
...qux.QuxTests...hello...
...qux.QuxTests...world...
...qux.QuxIntegrationTests...helloworld...

> mill __.integration.testCached # run all integration test suites

*/