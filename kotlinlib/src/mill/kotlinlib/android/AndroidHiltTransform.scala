package mill.kotlinlib.android

import coursier.Repository
import mill.define.{Discover, ExternalModule, PathRef}
import mill.javalib.android.AndroidSdkModule
import mill.scalalib.{Dep, JvmWorkerModule}
import mill.{T, Task}

/**
 * A module that can be used to run bytecode transformations
 * with [[mill.kotlinlib.android.hilt.AndroidHiltTransformAsm]] to
 * achieve Dependency Injection using Hilt .
 */
@mill.api.experimental
trait AndroidHiltTransform extends ExternalModule with JvmWorkerModule {

  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
  }

  /**
   * The classpath of the AndroidHiltTransformAsm executable.
   */
  def toolsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        Dep.millProjectModule("mill-kotlinlib-androidhilt")
      )
    )
  }

  /**
   * Transforms the Kotlin classes with Hilt dependency injection context
   * and returns the new path of the kotlin compiled classpath. This uses
   * the [[mill.kotlinlib.android.hilt.AndroidHiltTransformAsm]] that uses
   * the hilt gradle plugin and the android build tools.
   */
  def androidHiltTransformAsm(
      compiledClasses: Task[PathRef]
  ): Task[PathRef] = Task.Anon {

    val kotlinCompiledClassesDir = compiledClasses().path
    val transformedClasses = Task.dest / "transformed/classes"

    os.makeDir.all(transformedClasses)

    val mainClass = "mill.kotlinlib.android.hilt.AndroidHiltTransformAsm"

    mill.util.Jvm.callProcess(
      mainClass = mainClass,
      classPath = toolsClasspath().map(_.path),
      mainArgs = Seq(kotlinCompiledClassesDir.toString, transformedClasses.toString)
    )

    PathRef(transformedClasses)

  }

  override lazy val millDiscover: Discover = Discover[this.type]
}

object AndroidHiltTransform extends AndroidHiltTransform
