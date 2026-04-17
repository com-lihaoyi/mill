package mill.contrib.owaspdependencycheck

import mainargs.Flag
import mill.*
import mill.api.*
import mill.javalib.*

trait OwaspDependencyCheckModule extends Module, OfflineSupportModule {

  /**
   * The files to be scanned by the Dependency Check.
   * Like jars, package lock files etc.
   */
  def owaspDependencyCheckFiles: T[Seq[PathRef]] = Seq.empty

  /**
   * The dependency check flags, check the reference at https://dependency-check.github.io/DependencyCheck/dependency-check-cli/arguments.html
   * By default Seq("--nvdDatafeed", "https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/") is set.
   * The --scan flags are appended based on the files in [[owaspDependencyCheckFiles]]
   *
   * The --out dir is set by the [[owaspDependencyCheck]].
   *
   * @return
   */
  def owaspDependencyCheckConfigArgs: T[Seq[String]] =
    Seq("--nvdDatafeed", "https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/")

  /**
   * The worker is version specific, so is another version is required, it can be customized here.
   */
  val owaspDependencyCheckWorker: ModuleRef[OwaspDependencyCheckWorker] = ModuleRef(OwaspDependencyCheckWorker)

  /**
   * Be default true, then [[owaspDependencyCheck()]] will fail if the dependency scan fails (eg. --failOnCVSS).
   *
   * @return
   */
  def owaspDependencyCheckFailTask: Boolean = true

  case class DependencyCheckResult(reportFiles: Seq[PathRef], exitCode: Int)
    derives upickle.ReadWriter {
    def success: Boolean = exitCode == 0
  }

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    (super.prepareOffline(all)() ++ owaspDependencyCheckWorker().prepareOffline(all)()).distinct
  }

  /**
   * Run the dependency check
   *
   * @return
   */
  final def owaspDependencyCheck(): Task.Command[DependencyCheckResult] =
    Task.Command(exclusive = true) {
      val args = owaspDependencyCheckConfigArgs()
      val files = owaspDependencyCheckFiles()
      if (files.nonEmpty) {

        val scanDirectives = files.flatMap(p => Seq("--scan", p.path.toString))

        val arguments = args ++ scanDirectives ++ Seq("--out", Task.dest.toString)
        println(s"Final scan arguments to Dependency Check CLI: ${arguments.mkString(" ")}")
        val exitCode = owaspDependencyCheckWorker().worker().runScan(arguments)
        val result = DependencyCheckResult(os.list(Task.dest).map(PathRef(_)), exitCode)
        if (owaspDependencyCheckFailTask && !result.success) {
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
 * Java Dependency Check, that adds the resolvedRunMvnDeps path to be scanned in the dependency check.
 */
trait OwaspDependencyCheckJavaModule extends JavaModule, OwaspDependencyCheckModule {
  override def owaspDependencyCheckFiles: T[Seq[PathRef]] = Task {
    super.resolvedRunMvnDeps()
  }
}

trait OwaspDependencyCheckWorker extends CoursierModule, OfflineSupportModule {

  def dependencyCheckVersion: T[String]

  def dependencyCheckClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(mvn"org.owasp:dependency-check-cli:${dependencyCheckVersion()}")
    )
  }

  def worker: Worker[DependencyCheckInstance] = Task.Worker {
    new DependencyCheckInstance(dependencyCheckClasspath().map(_.path))
  }

  private[owaspdependencycheck] class DependencyCheckInstance(dependencyCheckClasspath: Seq[os.Path])
    extends AutoCloseable {
    val classLoader = mill.util.Jvm.createClassLoader(dependencyCheckClasspath)
    val depencencyCheckCli = classLoader.loadClass("org.owasp.dependencycheck.App")
    val appConstructor = depencencyCheckCli.getConstructor()
    val mainMethod = depencencyCheckCli.getMethod("run", classOf[Array[String]])

    def runScan(args: Seq[String]): Int = {
      val ctxLoader = Thread.currentThread().getContextClassLoader
      try {
        Thread.currentThread().setContextClassLoader(classLoader)
        val app = appConstructor.newInstance()
        mainMethod.invoke(app, args.to(Array)).asInstanceOf[Int]
      } finally {
        Thread.currentThread().setContextClassLoader(ctxLoader)
      }
    }

    def close() = {
      classLoader.close()
    }
  }

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    (super.prepareOffline(all)() ++ dependencyCheckClasspath()).distinct
  }
}

object OwaspDependencyCheckWorker extends ExternalModule, OwaspDependencyCheckWorker {
  def dependencyCheckVersion: T[String] = "12.2.0" // FIXME: source from build

  override lazy val millDiscover = Discover[this.type]
}