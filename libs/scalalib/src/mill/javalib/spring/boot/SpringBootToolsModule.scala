package mill.javalib.spring.boot

import java.net.{URL, URLClassLoader}

import mill.*
import mill.define.{Discover, ExternalModule}
import mill.javalib.spring.boot.worker.SpringBootTools
import mill.javalib.{CoursierModule, Dep, DepSyntax}
import mill.scalalib.api.Versions

trait SpringBootToolsModule extends CoursierModule {

  /**
   * The Spring-Boot tools version to use.
   * Defaults to the version which was used at built-time of this Mill release.
   */
  def springBootToolsVersion: T[String] = Task {
    Versions.springBuildToolsVersion
  }

  def springBootToolsIvyDeps: T[Seq[Dep]] = Agg(
    mvn"org.springframework.boot:spring-boot-loader-tools:${springBootToolsVersion()}"
  )

  private def fullWorkerIvyDeps: T[Seq[Dep]] = Task {
    springBootToolsIvyDeps() ++ Seq(
      mvn"${Versions.millSpringBootWorkerDep}"
    )
  }

  def springBootToolsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(fullWorkerIvyDeps())
  }

  def springBootToolsWorker: Worker[SpringBootTools] = Task.Worker {
    val cl =
      new URLClassLoader(
        springBootToolsClasspath().map(_.path.toIO.toURI().toURL()).iterator.toArray[URL],
        getClass().getClassLoader()
      )
    val className =
      classOf[SpringBootTools].getPackage().getName() + ".impl." + classOf[
        SpringBootTools
      ].getSimpleName() + "Impl"
    val impl = cl.loadClass(className)
    val ctr = impl.getConstructor()
    val worker = ctr.newInstance().asInstanceOf[SpringBootTools]
    if (worker.getClass().getClassLoader() != cl) {
      T.log.error(
        s"""Worker not loaded from worker classloader.
           |You should not add the ${Versions.millSpringBootWorkerDep} JAR to the mill build classpath""".stripMargin
      )
    }
    if (worker.getClass().getClassLoader() == classOf[SpringBootTools].getClassLoader()) {
      T.log.error("Worker classloader used to load interface and implementation")
    }
    worker
  }

}

object SpringBootToolsModule extends ExternalModule with SpringBootToolsModule {
  lazy val millDiscover = Discover[this.type]
}
