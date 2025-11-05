package mill.javalib.testrunner

import mill.api.daemon.internal.internal

@internal object DiscoverTestsMain {
  def apply(args0: mill.javalib.api.internal.ZincDiscoverTests): Seq[String] = {
    import args0.*
    mill.util.Jvm.withClassLoader(
      classPath = runCp,
      sharedPrefixes = Seq("sbt.testing.")
    ) { classLoader =>
      TestRunnerUtils
        .discoverTests(classLoader, Framework.framework(framework)(classLoader), testCp)
        .toSeq
        .map(_._1.getName())
        .map {
          case s if s.endsWith("$") => s.dropRight(1)
          case s => s
        }
    }
  }
}
