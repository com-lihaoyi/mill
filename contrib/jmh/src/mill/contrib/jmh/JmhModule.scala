package mill.contrib.jmh

import mill._, scalalib._, modules._

/**
 * This module provides an easy way to integrate <a href="https://openjdk.org/projects/code-tools/jmh/">JMH</a> benchmarking with Mill.
 *
 * Example configuration:
 * {{{
 * import mill._, scalalib._
 *
 * import $ivy.`com.lihaoyi::mill-contrib-jmh:$MILL_VERSION`
 * import contrib.jmh.JmhModule
 *
 * object foo extends ScalaModule with JmhModule {
 *   def scalaVersion = "2.13.8"
 *   def jmhCoreVersion = "1.35"
 * }
 * }}}
 *
 * Here are some sample commands:
 * - mill foo.runJmh             # Runs all detected jmh benchmarks
 * - mill foo.listJmhBenchmarks  # List detected jmh benchmarks
 * - mill foo.runJmh -h          # List available arguments to runJmh
 * - mill foo.runJmh regexp      # Run all benchmarks matching `regexp`
 *
 * For Scala JMH samples see:
 * [[https://github.com/sbt/sbt-jmh/tree/main/plugin/src/sbt-test/sbt-jmh/run/src/main/scala/org/openjdk/jmh/samples]].
 */
trait JmhModule extends JavaModule {

  def jmhCoreVersion: T[String]
  def jmhGeneratorByteCodeVersion: T[String] = jmhCoreVersion

  def ivyDeps = super.ivyDeps() ++ Agg(ivy"org.openjdk.jmh:jmh-core:${jmhCoreVersion()}")

  def runJmh(args: String*) =
    T.command {
      val (_, resources) = generateBenchmarkSources()
      Jvm.runSubprocess(
        "org.openjdk.jmh.Main",
        classPath = (runClasspath() ++ generatorDeps()).map(_.path) ++
          Seq(compileGeneratedSources().path, resources),
        mainArgs = args,
        workingDir = T.ctx().dest
      )
    }

  def listJmhBenchmarks(args: String*) = runJmh(("-l" +: args): _*)

  def compileGeneratedSources =
    T {
      val dest = T.ctx().dest
      val (sourcesDir, _) = generateBenchmarkSources()
      val sources = os.walk(sourcesDir).filter(os.isFile)

      os.proc(
        Jvm.jdkTool("javac"),
        sources.map(_.toString),
        "-cp",
        (runClasspath() ++ generatorDeps()).map(_.path.toString).mkString(
          java.io.File.pathSeparator
        ),
        "-d",
        dest
      ).call(dest)
      PathRef(dest)
    }

  // returns sources and resources directories
  def generateBenchmarkSources =
    T {
      val dest = T.ctx().dest

      val sourcesDir = dest / "jmh_sources"
      val resourcesDir = dest / "jmh_resources"

      os.remove.all(sourcesDir)
      os.makeDir.all(sourcesDir)
      os.remove.all(resourcesDir)
      os.makeDir.all(resourcesDir)

      Jvm.runSubprocess(
        "org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator",
        (runClasspath() ++ generatorDeps()).map(_.path),
        mainArgs = Seq(
          compile().classes.path.toString,
          sourcesDir.toString,
          resourcesDir.toString,
          "default"
        )
      )

      (sourcesDir, resourcesDir)
    }

  def generatorDeps = T {
    resolveDeps(
      T.task {
        val bind = bindDependency()
        Agg(ivy"org.openjdk.jmh:jmh-generator-bytecode:${jmhGeneratorByteCodeVersion()}").map(bind)
      }
    )()
  }
}
