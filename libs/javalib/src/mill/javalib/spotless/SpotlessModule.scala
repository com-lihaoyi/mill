package mill.javalib.spotless

import mainargs.Flag
import mill.constants.OutFiles.{out, bspOut}
import mill.api.*
import mill.api.internal.RootModule0
import mill.javalib.{CoursierModule, Dep, OfflineSupportModule}
import mill.util.Jvm

/**
 * Enables formatting files using [[https://github.com/diffplug/spotless Spotless]].
 * @note Any third party artifacts required by Spotless are resolved using [[defaultResolver]]
 *       as "runtime" dependencies.
 * @see
 *  - [[spotlessFormats]]
 *  - [[spotless]]
 *  - [[ratchet]]
 */
@mill.api.experimental
trait SpotlessModule extends CoursierModule, OfflineSupportModule {

  /**
   * Checks/fixes formatting in files, within the module folder, that differ in 2 Git trees.
   * @param check If set, an error is raised on format errors. Otherwise, formatting is fixed.
   * @param from Revision to compare against.
   * @param to Revision to compare. When empty,
   *           - index tree is used, if `staged` is set
   *           - working tree is used, otherwise
   * @note Only files matching a [[Format]] in [[spotlessFormats]] are actually formatted.
   * @see [[https://javadoc.io/doc/org.eclipse.jgit/org.eclipse.jgit/latest/org.eclipse.jgit/org/eclipse/jgit/lib/Repository.html#resolve(java.lang.String) Revision]]
   */
  def ratchet(
      check: Flag,
      staged: Flag,
      @mainargs.arg(positional = true)
      from: String = "HEAD",
      @mainargs.arg(positional = true)
      to: Option[String]
  ): Task.Command[Unit] = Task.Command {
    spotlessWorker().ratchet(check.value, staged.value, from, to)
  }

  /**
   * Checks/fixes formatting for files within the module folder that match a [[Format]]
   * specification in [[spotlessFormats]].
   * @param check If set, the command fails on format errors. Otherwise, formatting is fixed.
   */
  def spotless(check: Flag): Task.Command[Unit] = Task.Command {
    spotlessWorker().format(check.value)
  }

  /**
   * [[Format]] specifications. Defaults to value in `.spotless-formats.json`, if it exists.
   * Otherwise, list of
   *  - [[Format.defaultJava]]
   *  - [[Format.defaultKotlin]]
   *  - [[Format.defaultScala]]
   *
   * For each format specification, the module folder is used to
   *  - resolve any [[Format.RelPathRef relative references]]
   *  - relativize a file path for applying path matchers
   */
  def spotlessFormats: Task[Seq[Format]] = Task {
    BuildCtx.withFilesystemCheckerDisabled {
      import Format.*
      RelPathRef.withDynamicRoot(moduleDir) {
        val file = moduleDir / ".spotless-formats.json"
        if os.exists(file) then upickle.default.read[Seq[Format]](file.toNIO)
        else Seq(defaultJava, defaultKotlin, defaultScala)
      }
    }
  }

  /**
   * Global path matcher patterns, relative to module folder, for files/folders to exclude from formatting.
   * Defaults to Mill "out" folder, for a root module
   * @see [[https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String) Path matcher pattern]]
   */
  def spotlessExcludes: Task[Seq[String]] = this match
    case _: RootModule0 => Task { Seq(s"glob:$out", s"glob:$bspOut") }
    case _ => Task { Seq.empty[String] }

  private def spotlessWorkerClasspath = Task {
    defaultResolver().classpath(Seq(Dep.millProjectModule("mill-libs-javalib-spotless-worker")))
  }

  private def spotlessWorkerClassloader = Task.Worker {
    Jvm.createClassLoader(
      classPath = spotlessWorkerClasspath().map(_.path),
      parent = getClass.getClassLoader
    )
  }

  private def spotlessWorker = Task.Worker {
    BuildCtx.withFilesystemCheckerDisabled {
      spotlessWorkerClassloader()
        .loadClass("mill.javalib.spotless.SpotlessWorkerImpl")
        .getConstructor(
          classOf[os.Path],
          classOf[Seq[String]],
          classOf[Seq[Format]],
          classOf[CoursierModule.Resolver],
          classOf[TaskCtx]
        )
        .newInstance(
          moduleDir,
          spotlessExcludes(),
          spotlessFormats(),
          defaultResolver(),
          Task.ctx()
        )
        .asInstanceOf[SpotlessWorker]
    }
  }

  override def prepareOffline(all: Flag) = Task.Command {
    (
      super.prepareOffline(all)() ++
        spotlessWorkerClasspath() ++
        spotlessWorker().provision
    ).distinct
  }
}
@mill.api.experimental
object SpotlessModule extends ExternalModule, DefaultTaskModule, SpotlessModule {
  lazy val millDiscover = Discover[this.type]
  def defaultTask() = "spotless"
}
