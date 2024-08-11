import mill._, scalalib._

object foo extends ScalaModule with UnidocModule{
  def scalaVersion = "2.13.8"
  def moduleDeps = Seq(bar, qux)

  object bar extends ScalaModule{
    def scalaVersion = "2.13.8"
  }

  object qux extends ScalaModule {
    def scalaVersion = "2.13.8"
    def moduleDeps = Seq(bar)
  }

  def unidocVersion = Some("0.1.0")
  def unidocSourceUrl = Some("https://github.com/lihaoyi/test/blob/master")
}

// This example demonstrates use of `mill.scalalib.UnidocModule`. This can be
// mixed in to any `ScalaModule`, and generates a combined Scaladoc for the
// module and all its transitive dependencies. Two targets are provided:
//
// * `.unidocLocal`: this generates a site suitable for local browsing. If
//   unidocSourceUrl is provided, the scaladoc provides links back to the
//   local sources
//
// * `.unidocSite`: this generates a site suitable for local browsing. If
//   unidocSourceUrl is provided, the scaladoc provides links back to the
//   sources as browsable from the `unidocSourceUrl` base (e.g. on Github)

/** Usage

> ./mill show foo.unidocLocal
".../out/foo/unidocLocal.dest"

> cat out/foo/unidocLocal.dest/foo/Foo.html
...
...My Eloquent Scaladoc for Foo...

> cat out/foo/unidocLocal.dest/foo/qux/Qux.html
...
...My Excellent Scaladoc for Qux...

> cat out/foo/unidocLocal.dest/foo/bar/Bar.html
...
...My Lucid Scaladoc for Bar...

> ./mill show foo.unidocSite

*/