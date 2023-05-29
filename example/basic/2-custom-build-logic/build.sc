// Mill makes it very easy to customize your build graph, overriding portions
// of it with custom logic. In this example, we override the JVM `resources` of
// our `ScalaModule` - normally the `resources/` folder - to instead contain a
// single generated text file containing the line count of all the source files
// in that module
import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"

  /** Total number of lines in module's source files */
  def lineCount = T{
    allSourceFiles().map(f => os.read.lines(f.path).size).sum
  }

  /** Generate resources using lineCount of sources */
  override def resources = T{
    os.write(T.dest / "line-count.txt", "" + lineCount())
    Seq(PathRef(T.dest))
  }
}

/** Usage

> ./mill run
...
Line Count: 11

> ./mill show foo.lineCount
11

> ./mill inspect foo.lineCount
[1/1] inspect
foo.lineCount(build.sc:6)
    Total number of lines in module's source files

Inputs:
    foo.allSourceFiles
*/

// Above, `def lineCount` is a new build target we define, which makes use of
// `allSourceFiles` (an existing target) and is in-turn used in our override of
// `resources` (also an existing target). This generated file can then be
// loaded and used at runtime, as see in the output of `./mill run`
//
// While this is a toy example, it shows how easy it is to customize your Mill
// build to include the kinds of custom logic common in the build config of
// most real-world projects.
//
// This customization is done in a principled fashion familiar to most
// programmers - object-orienting overrides - rather than ad-hoc
// monkey-patching or mutation common in other build tools. You never have
// "spooky action at a distance" affecting your build / graph definition, and
// your IDE can always help you find the final override of any particular build
// target as well as where any overriden implementations may be defined.
//
// Lastly, custom user-defined targets in Mill benefit from all the same things
// that built-in targets do: caching, parallelism (with the `-j`/`--jobs`
// flag), inspectability via `show`/`inspect`, and so on.
//
// While these things may not matter for such a simple example that runs
// quickly, they ensure that custom build logic remains performant and
// maintainable even as the complexity of your project grows.
