//// SNIPPET:BUILD
import mill._, javalib._

trait MyModule extends JavaModule {
  def ivyDeps = Agg(
    ivy"net.sourceforge.argparse4j:argparse4j:0.9.0",
    ivy"org.apache.commons:commons-text:1.12.0"
  )
}

object foo extends MyModule {
  def moduleDeps = Seq(bar, qux)

  object bar extends MyModule
  object qux extends MyModule {
    def moduleDeps = Seq(bar)
  }
}

object baz extends MyModule {
  def moduleDeps = Seq(foo.bar, foo.qux, foo)
}
