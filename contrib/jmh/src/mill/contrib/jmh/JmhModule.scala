package mill.contrib.jmh

import mill._, javalib._
import mill.util.Jvm

/**
 * This module provides an easy way to integrate <a href="https://openjdk.org/projects/code-tools/jmh/">JMH</a> benchmarking with Mill.
 *
 * Example configuration:
 * {{{
 * //| mvnDeps: ["com.lihaoyi::mill-contrib-jmh:$MILL_VERSION"]
 *
 * import mill._, scalalib._
 *
 * import contrib.jmh.JmhModule
 *
 * object foo extends ScalaModule with JmhModule {
 *   def scalaVersion = "2.13.16"
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

  def mvnDeps = super.mvnDeps() ++ Seq(mvn"org.openjdk.jmh:jmh-core:${jmhCoreVersion()}")

  def runJmh(args: String*) =
    Task.Command {
      val (_, resources) = generateBenchmarkSources()
      Jvm.callProcess(
        mainClass = "org.openjdk.jmh.Main",
        classPath = (runClasspath() ++ generatorDeps()).map(_.path) ++
          Seq(jmhGeneratedSources().path, resources.path),
        mainArgs = args,
        cwd = Task.ctx().dest,
        javaHome = javaHome().map(_.path),
        stdin = os.Inherit,
        stdout = os.Inherit
      )
      ()
    }

  def listJmhBenchmarks(args: String*) = runJmh(("-l" +: args)*)

  def jmhGeneratedSources =
    Task {
      val dest = Task.ctx().dest
      val (sourcesDir, _) = generateBenchmarkSources()
      val sources = os.walk(sourcesDir.path).filter(os.isFile)

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
    Task {
      val dest = Task.ctx().dest
      val forkedArgs = forkArgs().toSeq
      val sourcesDir = dest / "jmh_sources"
      val resourcesDir = dest / "jmh_resources"

      os.remove.all(sourcesDir)
      os.makeDir.all(sourcesDir)
      os.remove.all(resourcesDir)
      os.makeDir.all(resourcesDir)

      Jvm.callProcess(
        mainClass = "org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator",
        classPath = (runClasspath() ++ generatorDeps()).map(_.path),
        mainArgs = Seq(
          compile().classes.path.toString,
          sourcesDir.toString,
          resourcesDir.toString,
          "default"
        ),
        javaHome = javaHome().map(_.path),
        jvmArgs = forkedArgs,
        stdin = os.Inherit,
        stdout = os.Inherit
      )

      (PathRef(sourcesDir), PathRef(resourcesDir))
    }

  def generatorDeps = Task {
    defaultResolver().classpath(
      Seq(mvn"org.openjdk.jmh:jmh-generator-bytecode:${jmhGeneratorByteCodeVersion()}")
    )
  }
}
