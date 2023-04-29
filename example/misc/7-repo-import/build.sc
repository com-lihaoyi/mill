import $repo.`file:///tmp/testrepo`
import mill._, mill.define._, mill.scalalib._
import coursier.Repository

//object foo extends RootModule {
def verify(): Command[Unit] = T.command {
  //  val repos: Seq[Repository] = build.asInstanceOf[ScalaModule].repositoriesTask()
  os.write(T.dest / "paths.txt", s"${T.workspace} || ${T.dest}")
  val model = os.read(T.workspace / "out" / "mill-build" / "parseBuildFiles.json")
  assert(model.contains("""file:///tmp/testrepo""""), "Model was unexpected: " + model)
}
//}

// This example illustrates usage of `import $file` and `import $ivy`. These
// allow you to pull in code from outside your `build.sc` file:
//
// 1. `import $file` lets you import other `*.sc` files on disk. This lets you
//    split up your `build.sc` logic if the file is growing too large. In this
//    tiny example case, we move `myScalaVersion` to another `versions.sc` file
//    and import it for use.
//
// 2. `import $ivy` lets you import ivy dependencies into your `build.sc`, so
//    you can use arbitrary third-party libraries at build-time. This makes
//    lets you perform computations at build-time rather than run-time,
//    speeding up your application start up. In this case, we move the
//    Scalatags rendering logic to build time, so the application code gets a
//    pre-rendere string it can directly print without further work.

/** Usage

> ./mill verify

*/
