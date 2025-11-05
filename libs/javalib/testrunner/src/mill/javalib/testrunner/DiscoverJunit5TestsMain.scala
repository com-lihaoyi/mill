package mill.javalib.testrunner

import mill.api.daemon.internal.internal

@internal object DiscoverJunit5TestsMain {

  def apply(args0: mill.javalib.api.internal.ZincDiscoverJunit5Tests): Seq[String] = {
    import args0.*
    mill.util.Jvm.withClassLoader(
      classPath = runCp,
      sharedPrefixes = Seq("sbt.testing.")
    ) { classLoader =>
      val builderClass: Class[?] =
        classLoader.loadClass("com.github.sbt.junit.jupiter.api.JupiterTestCollector$Builder")
      val builder = builderClass.getConstructor().newInstance()

      classesDir.foreach { path =>
        builderClass.getMethod("withClassDirectory", classOf[java.io.File]).invoke(
          builder,
          path.wrapped.toFile
        )
      }

      builderClass.getMethod("withRuntimeClassPath", classOf[Array[java.net.URL]]).invoke(
        builder,
        testCp.map(_.toURL).toArray
      )
      builderClass.getMethod("withClassLoader", classOf[ClassLoader]).invoke(builder, classLoader)

      val testCollector = builderClass.getMethod("build").invoke(builder)
      val testCollectorClass =
        classLoader.loadClass("com.github.sbt.junit.jupiter.api.JupiterTestCollector")

      val result = testCollectorClass.getMethod("collectTests").invoke(testCollector)
      val resultClass =
        classLoader.loadClass("com.github.sbt.junit.jupiter.api.JupiterTestCollector$Result")

      val items = resultClass.getMethod(
        "getDiscoveredTests"
      ).invoke(result).asInstanceOf[java.util.List[?]]
      val itemClass =
        classLoader.loadClass("com.github.sbt.junit.jupiter.api.JupiterTestCollector$Item")

      import scala.jdk.CollectionConverters._
      items.asScala.map { item =>
        itemClass.getMethod("getFullyQualifiedClassName").invoke(item).asInstanceOf[String]
      }.toSeq
    }
  }
}
