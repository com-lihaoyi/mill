package mill.contrib.dependencycheck

import mill.*
import mill.api.*
import mill.javalib.*

trait DependencyCheckModule extends Module {

  /**
   * The files to be scanned by the Dependency Check.
   * Like jars, package lock files etc.
   */
  def dependencyCheckFiles: T[Seq[PathRef]] = Seq.empty

  /**
   * The dependency check flags, check the reference at https://dependency-check.github.io/DependencyCheck/dependency-check-cli/arguments.html
   * By default Seq("--nvdDatafeed", "https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/") is set.
   * The --scan flags are appended based on the files in [[dependencyCheckFiles]]
   *
   * The --out dir is set by the [[dependencyCheck]].
   * @return
   */
  def dependencyCheckConfigArgs: T[Seq[String]] =
    Seq("--nvdDatafeed", "https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/")

  /**
   * Be default true, then [[dependencyCheck()]] will fail if the dependency scan fails (eg. --failOnCVSS).
   * @return
   */
  def dependencyCheckFailTask: Boolean = true

  case class DependencyCheckResult(reportFiles: Seq[PathRef], exitCode: Int)
      derives upickle.ReadWriter {
    def success: Boolean = exitCode == 0
  }

  /**
   * Run the dependency check
   * @return
   */
  final def dependencyCheck(): Task.Command[DependencyCheckResult] =
    Task.Command(exclusive = true) {
      val args = dependencyCheckConfigArgs()
      val files = dependencyCheckFiles()
      if (files.nonEmpty) {

        val scanDirectives = files.flatMap(p => Seq("--scan", p.path.toString))

        val arguments = args ++ scanDirectives ++ Seq("--out", Task.dest.toString)
        println(s"Final scan arguments to Dependency Check CLI: ${arguments.mkString(" ")}")
        val exitCode = DependencyCheckWorker.worker().runScan(arguments)
        val result = DependencyCheckResult(os.list(Task.dest).map(PathRef(_)), exitCode)
        if (dependencyCheckFailTask && !result.success) {
          throw new Exception(s"Dependency Check failed with status code $exitCode")
        }
        result
      } else {
        println("No files to scan. Skip dependency check")
        DependencyCheckResult(Seq.empty, 0)
      }
    }
}

/**
 * Java Dependency Check, that adds the runtime class path to be scanned in the dependency check.
 */
trait DependencyCheckJavaModule extends JavaModule with DependencyCheckModule {
  override def dependencyCheckFiles: T[Seq[PathRef]] = Task {
    super.runClasspath().filter(p => p.path.last.endsWith(".jar"))
  }
}

object DependencyCheckWorker extends ExternalModule with CoursierModule with OfflineSupportModule {
  lazy val millDiscover = Discover[this.type]
  def dependencyCheckClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(mvn"org.owasp:dependency-check-cli:12.2.0")
    )
  }

  def dependencyCheckClassLoader: Worker[java.net.URLClassLoader] = Task.Worker {
    mill.util.Jvm.createClassLoader(dependencyCheckClasspath().map(_.path))
  }

  def worker: Worker[DependencyCheckInstance] =
    Task.Worker { new DependencyCheckInstance(dependencyCheckClassLoader()) }

  private[dependencycheck] class DependencyCheckInstance(cl: ClassLoader) extends AutoCloseable {
    val depencencyCheckCli = cl.loadClass("org.owasp.dependencycheck.App")
    val appConstructor = depencencyCheckCli.getConstructor()
    val mainMethod = depencencyCheckCli.getMethod("run", classOf[Array[String]])

    def runScan(args: Seq[String]): Int = {
      val ctxLoader = Thread.currentThread().getContextClassLoader
      try {
        Thread.currentThread().setContextClassLoader(cl)
        val app = appConstructor.newInstance()
        mainMethod.invoke(app, args.to(Array)).asInstanceOf[Int]
      } finally {
        Thread.currentThread().setContextClassLoader(ctxLoader)
      }
    }

    def close() = {
      // No-op
    }
  }
}
