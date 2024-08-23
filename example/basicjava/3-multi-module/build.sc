//// SNIPPET:BUILD

import mill._, javalib._

trait MyModule extends JavaModule{
  object test extends JavaTests with TestModule.Junit4
}

object foo extends MyModule{
  def moduleDeps = Seq(bar)
  def ivyDeps = Agg(
    ivy"net.sourceforge.argparse4j:argparse4j:0.9.0",
  )
}

object bar extends MyModule{
  def ivyDeps = Agg(
    ivy"net.sourceforge.argparse4j:argparse4j:0.9.0",
    ivy"org.apache.commons:commons-text:1.12.0"
  )
}

//// SNIPPET:TREE
// ----
// build.sc
// foo/
//     src/
//         foo/
//             Foo.java
//     resources/
//         ...
// bar/
//     src/
//         bar/
//             Bar.java
//     resources/
//         ...
// out/
//     foo/
//         compile.json
//         compile.dest/
//         ...
//     bar/
//         compile.json
//         compile.dest/
//         ...
// ----
//
