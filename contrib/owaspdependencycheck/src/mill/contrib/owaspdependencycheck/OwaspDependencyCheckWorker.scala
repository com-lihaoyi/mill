package mill.contrib.owaspdependencycheck

import mainargs.Flag
import mill.api.Task.Command
import mill.api.Task.{Worker, Simple as T}
import mill.api.{Discover, ExternalModule, PathRef, Task}
import mill.javalib.{CoursierModule, DepSyntax, OfflineSupportModule}

trait OwaspDependencyCheckWorker extends CoursierModule, OfflineSupportModule {

  def dependencyCheckVersion: T[String]

  def dependencyCheckClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(mvn"org.owasp:dependency-check-cli:${dependencyCheckVersion()}")
    )
  }

  def worker: Worker[DependencyCheckInstance] = Task.Worker {
    DependencyCheckInstance(dependencyCheckClasspath().map(_.path))
  }

  private[owaspdependencycheck] class DependencyCheckInstance(
      dependencyCheckClasspath: Seq[os.Path]
  ) extends AutoCloseable {
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
  def dependencyCheckVersion: T[String] = BuildInfo.owaspDependencyCheckVersion

  override lazy val millDiscover = Discover[this.type]
}
