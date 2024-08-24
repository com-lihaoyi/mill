import $meta._
import mill._, scalalib._
import scalatags.Text.all._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.4"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::scalatags:${millbuild.DepVersions.scalatagsVersion}",
    ivy"com.lihaoyi::os-lib:0.9.1"
  )

  def htmlSnippet = T{ h1("hello").toString }
  def resources = T.sources{
    os.write(T.dest / "snippet.txt", htmlSnippet())
    super.resources() ++ Seq(PathRef(T.dest))
  }
}

// This example illustrates usage of the `mill-build/` folder. Mill's `build.sc`
// file and it's `import $file` and `$ivy` are a shorthand syntax for defining
// a Mill `ScalaModule`, with sources and `ivyDeps` and so on, which is
// compiled and executed to perform your build. This "meta-build" module lives in
// `mill-build/`, and can be enabled via the `import $meta._` statement above.

/** See Also: mill-build/build.sc */
/** See Also: src/Foo.scala */

// Typically, if you wanted to use a library such as Scalatags in both your `build.sc`
// as well as your application code, you would need to duplicate the version of Scalatags
// used in both your `import $ivy` statement as well as your `def ivyDeps` statement.
// The meta-build lets us avoid this duplication: we use `generatedSources` in
// `mill-build/build.sc` to create a `DepVersions` object that the `build.sc` can use to pass the
// `scalatagsVersion` to the application without having to copy-paste the
// version and keep the two copies in sync.
//
// When we run the application, we can see both the `Build-time HTML snippet` and the
// `Run-time HTML snippet` being printed out, both using the same version of Scalatags
// but performing the rendering of the HTML in two different places:

/** Usage

> mill compile
...

> mill run
Build-time HTML snippet: <h1>hello</h1>
Run-time HTML snippet: <p>world</p>

> mill show assembly
".../out/assembly.dest/out.jar"

> ./out/assembly.dest/out.jar # mac/linux
Build-time HTML snippet: <h1>hello</h1>
Run-time HTML snippet: <p>world</p>

*/


// This example is trivial, but in larger builds there may be much more scenarios
// where you may want to keep the libraries used in your `build.sc` and the libraries
// used in your application code consistent. With the `mill-build/build.sc` configuration
// enabled by `import $meta._`, this becomes possible.
//
// You can customize the `mill-build/` module with more flexibility than is
// provided by `import $ivy` or `import $file`, overriding any tasks that are
// present on a typical `ScalaModule`: `scalacOptions`, `generatedSources`, etc.
// This is useful for large projects where the build itself is a non-trivial
// module which requires its own non-trivial customization.


// You can also run tasks on the meta-build by using the `--meta-level`
// cli option.

/** Usage

> mill --meta-level 1 show sources
[
.../build.sc",
.../mill-build/src"
]

> mill --meta-level 2 show sources
.../mill-build/build.sc"


*/