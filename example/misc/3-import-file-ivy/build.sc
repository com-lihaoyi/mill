import mill._, scalalib._
import $ivy.`com.lihaoyi::scalatags:0.8.2`, scalatags.Text.all._
import $file.scalaversion, scalaversion.myScalaVersion

object foo extends BuildFileModule with ScalaModule {
  def scalaVersion = myScalaVersion

  def ivyDeps = Agg(ivy"com.lihaoyi::os-lib:0.9.1")
  def htmlSnippet = T{ h1("hello").toString }
  def resources = T.sources{
    os.write(T.dest / "snippet.txt", htmlSnippet())
    super.resources() ++ Seq(PathRef(T.dest))
  }
}

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


/* Example Usage

> ./mill compile
compiling 1 Scala source

> ./mill run
Foo.value: <h1>hello</h1>

> ./mill show assembly # mac/linux
out/assembly.dest/out.jar

> ./out/assembly.dest/out.jar # mac/linux
Foo.value: <h1>hello</h1>

> ./out/assembly.dest/out.bat # windows
Foo.value: <h1>hello</h1>

*/
