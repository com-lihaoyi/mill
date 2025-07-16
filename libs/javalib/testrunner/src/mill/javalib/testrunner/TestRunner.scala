package mill.javalib.testrunner

import mill.api.TaskCtx
import mill.api.daemon.internal.{TestReporter, internal}
import mill.util.Jvm

@internal object TestRunner {

  def runTestFramework(
      frameworkInstances: ClassLoader => sbt.testing.Framework,
      entireClasspath: Seq[os.Path],
      testClassfilePath: Seq[os.Path],
      args: Seq[String],
      testReporter: TestReporter,
      classFilter: Class[?] => Boolean = _ => true
  )(implicit ctx: TaskCtx.Log): (String, Seq[TestResult]) = {
    Jvm.withClassLoader(
      classPath = entireClasspath.toVector,
      sharedPrefixes = Seq("sbt.testing.", "mill.api.daemon.internal.TestReporter")
    ) { classLoader =>
      TestRunnerUtils.runTestFramework0(
        frameworkInstances,
        testClassfilePath,
        args,
        classFilter,
        classLoader,
        testReporter
      )
    }
  }
}
