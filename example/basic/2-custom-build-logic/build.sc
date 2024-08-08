// Mill makes it very easy to customize your build graph, overriding portions
// of it with custom logic. In this example, we override the JVM `resources` of
// our `ScalaModule` - normally the `resources/` folder - to instead contain a
// single generated text file containing the line count of all the source files
// in that module

//// SNIPPET:BUILD
import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.11"

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

//// SNIPPET:END

// The addition of `lineCount` and `resources` overrides the previous `resource`
// folder provided by `JavaModule` (labelled `resource.super` below), replacing
// it with the destination folder of the new `resources` target, which is wired
// up `lineCount`:
//
// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   allSourceFiles -> lineCount -> resources -> "..." -> run
//   "resources.super" -> "..." [style=invis]
//   "..." [color=white]
//   "resources.super" [style=dashed]
//   allSourceFiles [color=white]
//   run [color=white]
// }
// ....


/** Usage

> mill run
...
Line Count: 17

> mill show lineCount
17

> mill inspect lineCount
lineCount(build.sc:...)
    Total number of lines in module's source files
Inputs:
    allSourceFiles
*/

// Above, `def lineCount` is a new build target we define, which makes use of
// `allSourceFiles` (an existing target) and is in-turn used in our override of
// `resources` (also an existing target). `os.read.lines` and `os.write come
// from the https://github.com/com-lihaoyi/os-lib[OS-Lib] library, which is
// bundled with Mill. This generated file can then be
// loaded and used at runtime, as see in the output of `mill run`
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
// Unlike normal methods, custom user-defined targets in Mill benefit from all
// the same things that built-in targets do: automatic caching, parallelism
// (with the `-j`/`--jobs` flag), inspectability (via `show`/`inspect`), and so on.
//
// While these things may not matter for such a simple example that runs
// quickly, they ensure that custom build logic remains performant and
// maintainable even as the complexity of your project grows.
