package mill.javalib.spring.boot.worker

import mill.api.TaskCtx

@mill.api.daemon.experimental
trait SpringBootTools extends AutoCloseable {
  def repackageJar(
      dest: os.Path,
      base: os.Path,
      mainClass: String,
      libs: Seq[os.Path],
      assemblyScript: Option[String]
  )(using ctx: TaskCtx): Unit

  /**
   * Find a SpringBootApplication entry point.
   * @param classesPath
   */
  def findSpringBootApplicationClass(classesPath: Seq[os.Path]): Either[String, String]
}
