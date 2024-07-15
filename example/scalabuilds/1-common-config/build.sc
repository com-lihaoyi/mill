// This example shows some of the common tasks you may want to override on a
// `ScalaModule`: specifying the `mainClass`, adding additional
// sources/resources, generating resources, and setting compilation/run
// options.

// SNIPPET:BUILD
import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"

  // You can have arbitrary numbers of third-party dependencies
  def ivyDeps = Agg(
    ivy"com.lihaoyi::scalatags:0.8.2",
    ivy"com.lihaoyi::os-lib:0.9.1",
  )

  // Choose a main class to use for `.run` if there are multiple present
  def mainClass: T[Option[String]] = Some("foo.Foo2")

  // Add (or replace) source folders for the module to use
  def sources = T.sources{
    super.sources() ++ Seq(PathRef(millSourcePath / "custom-src"))
  }

  // Add (or replace) resource folders for the module to use
  def resources = T.sources{
    super.resources() ++ Seq(PathRef(millSourcePath / "custom-resources"))
  }

  // Generate sources at build time
  def generatedSources: T[Seq[PathRef]] = T {
    for(name <- Seq("A", "B", "C")) os.write(
      T.dest / s"Foo$name.scala",
      s"""package foo
         |object Foo$name {
         |  val value = "hello $name"
         |}
         |""".stripMargin
    )

    Seq(PathRef(T.dest))
  }

  // Pass additional JVM flags when `.run` is called or in the executable
  // generated by `.assembly`
  def forkArgs: T[Seq[String]] = Seq("-Dmy.custom.property=my-prop-value")

  // Pass additional environmental variables when `.run` is called. Note that
  // this does not apply to running externally via `.assembly
  def forkEnv: T[Map[String, String]] = Map("MY_CUSTOM_ENV" -> "my-env-value")

  // Additional Scala compiler options, e.g. to turn warnings into errors
  def scalacOptions: T[Seq[String]] = Seq("-deprecation", "-Xfatal-warnings")
}
// SNIPPET:END

//
// Note the use of `millSourcePath`, `T.dest`, and `PathRef` when preforming
// various filesystem operations:
//
// 1. `millSourcePath` refers to the base path of the module. For the root
//    module, this is the root of the repo, and for inner modules it would be
//    the module path e.g. for module `foo.bar.qux` the `millSourcePath` would
//    be `foo/bar/qux`. This can also be overriden if necessary
//
// 2. `T.dest` refers to the destination folder for a task in the `out/`
//    folder. This is unique to each task, and can act as both a scratch space
//    for temporary computations as well as a place to put "output" files,
//    without worrying about filesystem conflicts with other tasks
//
// 3. `PathRef` is a way to return the *contents* of a file or folder, rather
//    than just its path as a string. This ensures that downstream tasks
//    properly invalidate when the contents changes even when the path stays
//    the same

/** Usage

> mill run
Foo2.value: <h1>hello2</h1>
Foo.value: <h1>hello</h1>
FooA.value: hello A
FooB.value: hello B
FooC.value: hello C
MyResource: My Resource Contents
MyOtherResource: My Other Resource Contents
my.custom.property: my-prop-value
MY_CUSTOM_ENV: my-env-value

> mill show assembly
".../out/assembly.dest/out.jar"

> ./out/assembly.dest/out.jar # mac/linux
Foo2.value: <h1>hello2</h1>
Foo.value: <h1>hello</h1>
FooA.value: hello A
FooB.value: hello B
FooC.value: hello C
MyResource: My Resource Contents
MyOtherResource: My Other Resource Contents
my.custom.property: my-prop-value

*/

// SNIPPET:FATAL_WARNINGS

/** Usage

> sed -i 's/Foo2 {/Foo2 { println(this + "hello")/g' custom-src/Foo2.scala

> mill compile # demonstrate -deprecation/-Xfatal-warnings flags
error: object Foo2 { println(this + "hello")
error:                       ^
error: ...Implicit injection of + is deprecated. Convert to String to call +...

*/
