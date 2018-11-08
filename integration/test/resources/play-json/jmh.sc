
import mill._, scalalib._, modules._

trait Jmh extends ScalaModule {

  def ivyDeps = super.ivyDeps() ++ Agg(ivy"org.openjdk.jmh:jmh-core:1.19")

  def runJmh(args: String*) = T.command {
    val (_, resources) = generateBenchmarkSources()
    Jvm.runSubprocess(
      "org.openjdk.jmh.Main",
      classPath = (runClasspath() ++ generatorDeps()).map(_.path) ++
        Seq(compileGeneratedSources().path, resources),
      mainArgs = args,
      workingDir = T.ctx.dest
    )
  }

  def compileGeneratedSources = T {
    val dest = T.ctx.dest
    val (sourcesDir, _) = generateBenchmarkSources()
    val sources = os.walk(sourcesDir).filter(os.isFile)
    os.proc("javac",
       sources.map(_.toString),
       "-cp",
       (runClasspath() ++ generatorDeps()).map(_.path.toString).mkString(":"),
       "-d",
       dest).call(dest)
    PathRef(dest)
  }

  // returns sources and resources directories
  def generateBenchmarkSources = T {
    val dest = T.ctx().dest

    val sourcesDir = dest / 'jmh_sources
    val resourcesDir = dest / 'jmh_resources

    os.remove.all(sourcesDir)
    os.makeDir.all(sourcesDir)
    os.remove.all(resourcesDir)
    os.makeDir.all(resourcesDir)

    Jvm.runSubprocess(
      "org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator",
      (runClasspath() ++ generatorDeps()).map(_.path),
      mainArgs = Array(
        compile().classes.path,
        sourcesDir,
        resourcesDir,
        "default"
      ).map(_.toString)
    )

    (sourcesDir, resourcesDir)
  }

  def generatorDeps = resolveDeps(
    T { Agg(ivy"org.openjdk.jmh:jmh-generator-bytecode:1.19") }
  )
}
