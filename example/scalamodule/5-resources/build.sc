//// SNIPPET:BUILD
import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::os-lib:0.9.1"
  )

  object test extends ScalaTests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.8.4")
    def testFramework = "utest.runner.Framework"

    def otherFiles = T.source(millSourcePath / "other-files")

    def forkEnv = super.forkEnv() ++ Map(
      "OTHER_FILES_FOLDER" -> otherFiles().path.toString
    )
  }
}
//// SNIPPET:END

/** Usage

> ./mill foo.test
... foo.FooTests.simple ...
...

*/

// This section discusses how tests can depend on resources locally on disk.
// Mill provides two ways to do this: via the JVM classpath resources, and via
// the resource folder which is made available as the environment variable
// `TEST_MILL_RESOURCE_FOLDER`;
//
// * The *classpath resources* are useful when you want to fetch individual files,
//   and are bundled with the application by the `.assembly` step when constructing
//   an assembly jar for deployment. But they do not allow you to list folders
//   or perform other filesystem operations.
//
// * The *resource folder*, available via `TEST_MILL_RESOURCE_FOLDER`, gives you
//   access to the folder path of the resources on disk. This is useful in allowing
//   you to list and otherwise manipulate the filesystem, which you cannot do with
//   *classpath resources*. However, the `TEST_MILL_RESOURCE_FOLDER` only exists
//   when running tests using Mill, and is not available when executing applications
//   packaged for deployment via `.assembly`
//
// * Apart from `resources/`, you can provide additional folders to your test suite
//   by defining a `T.source` (`otherFiles` above) and passing it to `forkEnv`. This
//   provide the folder path as an environment variable that the test can make use of
//
// You can click the *browse* button in the above example to see an example of code
// the uses these three approaches to load files as part of a test module.
//
// Note that tests require that you pass in any files that they depend on explicitly.
// This is necessary so that Mill knows when a test needs to be re-run and when a
// previous result can be cached. This also ensures that tests reading and writing
// to the current working directory do not accidentally interfere with each others
// files, especially when running in parallel.
//
// The test process runs in a `sandbox/` folder, not in your project root folder, to
// prevent you from accidentally accessing files without explicitly passing them. If
// you have legacy tests that need to run in the project root folder to work, you
// can configure your test suite with `def testSandboxWorkingDir = false` to disable
// the sandbox and make the tests run in the project root.




