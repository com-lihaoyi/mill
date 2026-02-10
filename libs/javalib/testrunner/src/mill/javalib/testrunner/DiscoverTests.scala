package mill.javalib.testrunner

import mill.api.daemon.internal.internal

@internal object DiscoverTests {
  def apply(args0: mill.javalib.api.internal.ZincOp.DiscoverTests): Seq[String] = {
    import args0.*
    val normalizedRunCp = runCp.map(PathNormalization.normalizePath)
    val normalizedTestCp = testCp.map(PathNormalization.normalizePath)
    mill.util.Jvm.withClassLoader(
      classPath = normalizedRunCp,
      sharedPrefixes = Seq("sbt.testing.")
    ) { classLoader =>
      TestRunnerUtils
        .discoverTests(classLoader, Framework.framework(framework)(classLoader), normalizedTestCp)
        .toSeq
        .map(_._1.getName())
        .map {
          case s if s.endsWith("$") => s.dropRight(1)
          case s => s
        }
    }
  }
}
