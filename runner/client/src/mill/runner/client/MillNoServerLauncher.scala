package mill.runner.client

import java.lang.reflect.Method

object MillNoServerLauncher {

  case class LoadResult(millMainMethod: Option[Method], loadTime: Long) {
    val canLoad: Boolean = millMainMethod.isDefined
  }

  private var canLoad: Option[LoadResult] = None

  def load(): LoadResult = {
    canLoad.getOrElse {
      val startTime = System.currentTimeMillis()
      val millMainMethod: Option[Method] =
        try {
          val millMainClass = getClass.getClassLoader.loadClass("mill.runner.MillMain")
          val mainMethod = millMainClass.getMethod("main", classOf[Array[String]])
          Some(mainMethod)
        } catch {
          case _: ClassNotFoundException | _: NoSuchMethodException => None
        }

      val loadTime = System.currentTimeMillis() - startTime
      val result = LoadResult(millMainMethod, loadTime)
      canLoad = Some(result)
      result
    }
  }

  @throws[Exception]
  def runMain(args: Array[String]): Unit = {
    val loadResult = load()
    loadResult.millMainMethod match {
      case Some(_) =>
        val exitVal = MillProcessLauncher.launchMillNoServer(args)
        System.exit(exitVal)
      case None =>
        throw new RuntimeException("Cannot load mill.runner.MillMain class")
    }
  }
}
