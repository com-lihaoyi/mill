import mill._, javalib._, util.Jvm

object foo extends RootModule with JavaModule {
  // Additional source folder to put C sources
  def nativeSources = T.sources(millSourcePath / "native-src")

  // Auto-generate JNI `.h` files from Java classes using Javac
  def nativeHeaders = T {
    os.proc(Jvm.jdkTool("javac"), "-h", T.dest, "-d", T.dest.toString, allSourceFiles().map(_.path)).call()
    PathRef(T.dest)
  }

  // Compile C
  def nativeCompiled = T{
    val cSourceFiles = nativeSources().map(_.path).flatMap(os.walk(_)).filter(_.ext == "c")
    val output = "libhelloworld.so"
    os.proc(
        "clang", "-shared", "-fPIC",
        "-I" + nativeHeaders().path, //
        "-I" + sys.props("java.home") + "/include/", // global JVM header files
        "-I" + sys.props("java.home") + "/include/darwin",
        "-I" + sys.props("java.home") + "/include/linux",
        "-o", T.dest / output,
        cSourceFiles
      )
      .call(stdout = os.Inherit)

    PathRef(T.dest / output)
  }

  def forkEnv = Map("HELLO_WORLD_BINARY" -> nativeCompiled().path.toString)

  object test extends JavaTests with TestModule.Junit4{
    def forkEnv = Map("HELLO_WORLD_BINARY" -> nativeCompiled().path.toString)
  }
}

// This is an example of how use Mill to compile C code together with your Java
// code using JNI. There are three main steps: defining the C source folder,
// generating the header files using `javac`, and then compiling the C code
// using `clang`. After that we have the `libhelloworld.so` on disk ready to use,
// and in this example we use an environment variable to pass the path of that
// file to the application code to load it using `System.load`.
//
// This example is pretty minimal, but it demonstrates the core principles, and
// can be extended if necessary to more elaborate use cases. The `native*` tasks
// can also be extracted out into a `trait` for re-use if you have multiple
// `JavaModule`s that need native C components

/** Usage

> ./mill run
Hello, World!

> ./mill test
Test foo.HelloWorldTest.testSimple started
Test foo.HelloWorldTest.testSimple finished...
...
*/
