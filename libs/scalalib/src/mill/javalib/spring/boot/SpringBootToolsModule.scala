package mill.javalib.spring.boot

import java.net.{URL, URLClassLoader}
import mill.*
import mill.define.{Discover, ExternalModule}
import mill.javalib.spring.boot.worker.SpringBootTools
import mill.javalib.{CoursierModule, Dep, DepSyntax}
import mill.scalalib.api.Versions
import mill.api.MillURLClassLoader

trait SpringBootToolsModule extends CoursierModule {

  /**
   * The Spring-Boot tools version to use.
   * Defaults to the version which was used at built-time of this Mill release.
   */
  def springBootToolsVersion: T[String] = Task {
    Versions.springBuildToolsVersion
  }

  def springBootToolsDeps: T[Seq[Dep]] = Seq(
    mvn"org.springframework.boot:spring-boot-loader-tools:${springBootToolsVersion()}"
  )

  private def fullWorkerDeps: T[Seq[Dep]] = Task {
    springBootToolsDeps() ++ Seq(
      mvn"${Versions.millSpringBootWorkerDep}"
    )
  }

  def springBootToolsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(fullWorkerDeps())
  }

  def springBootToolsClassLoader: Worker[ClassLoader] = Task.Worker {
    mill.util.Jvm.createClassLoader(
      springBootToolsClasspath().map(_.path),
      getClass().getClassLoader()
    )
  }

  def springBootToolsWorker: Worker[SpringBootTools] = Task.Worker {
    val cl = springBootToolsClassLoader()
    val className =
      classOf[SpringBootTools].getPackage().getName() + ".impl." +
        classOf[SpringBootTools].getSimpleName() + "Impl"

    val worker = cl
      .loadClass(className)
      .getConstructor()
      .newInstance()
      .asInstanceOf[SpringBootTools]

    if (worker.getClass().getClassLoader() != cl) {
      Task.log.error(
        s"""|Worker was not loaded from worker classloader.
            |You should not add the ${Versions.millSpringBootWorkerDep} JAR to the mill build classpath"""
          .stripMargin
      )
    }
    if (worker.getClass().getClassLoader() == classOf[SpringBootTools].getClassLoader()) {
      Task.log.error("Same worker classloader was used to load interface and implementation")
    }
    worker
  }

}

object SpringBootToolsModule extends ExternalModule with SpringBootToolsModule {
  lazy val millDiscover = Discover[this.type]
}
