package mill.javalib.testrunner

import sbt.testing.*
import utest.*

object TestRunnerUtilsTests extends TestSuite {

  class BrokenReflectionClassLoader(parent: ClassLoader) extends ClassLoader(parent) {
    private val syntheticTypes = Set("non.existent.SyntheticType", "non.existent.SyntheticAnnotation")

    override def loadClass(name: String): Class[?] = {
      if (syntheticTypes.contains(name)) {
        throw new NoClassDefFoundError(name.replace('.', '/'))
      } else {
        super.loadClass(name)
      }
    }
  }

  class BrokenClass

  class ErrorTriggeringFramework extends Framework {
    def fingerprints(): Array[Fingerprint] = Array(
      new SubclassFingerprint {
        def isModule: Boolean = false
        def superclassName(): String = "non.existent.SyntheticType"
        def requireNoArgConstructor(): Boolean = true
      }
    )
    def name(): String = "ErrorTriggeringFramework"
    def runner(
        args: Array[String],
        remoteArgs: Array[String],
        testClassLoader: ClassLoader
    ): Runner = null
  }

  class AnnotationErrorFramework extends Framework {
    def fingerprints(): Array[Fingerprint] = Array(
      new AnnotatedFingerprint {
        def isModule: Boolean = false
        def annotationName(): String = "non.existent.SyntheticAnnotation"
      }
    )
    def name(): String = "AnnotationErrorFramework"
    def runner(
        args: Array[String],
        remoteArgs: Array[String],
        testClassLoader: ClassLoader
    ): Runner = null
  }

  def tests = Tests {
    test("discoverTests gracefully skips classes that trigger NoClassDefFoundError") {
      val cl = new BrokenReflectionClassLoader(getClass.getClassLoader)
      val framework = new ErrorTriggeringFramework()

      val tmpDir = os.temp.dir()
      val classFile = tmpDir / "mill" / "javalib" / "testrunner" /
        "TestRunnerUtilsTests$BrokenClass.class"
      os.makeDir.all(classFile / os.up)
      val realClassFile = os.Path(
        classOf[BrokenClass]
          .getProtectionDomain
          .getCodeSource
          .getLocation
          .toURI
      )
      if (os.isDir(realClassFile)) {
        val src = realClassFile / "mill" / "javalib" / "testrunner" /
          "TestRunnerUtilsTests$BrokenClass.class"
        if (os.exists(src)) os.copy(src, classFile)
      }

      val result = TestRunnerUtils.discoverTests(
        cl,
        framework,
        classpath = Seq(tmpDir),
        discoveredTestClasses = None
      )
      assert(result.isEmpty)
    }

    test("matchFingerprints returns None on NoClassDefFoundError") {
      val cl = new BrokenReflectionClassLoader(getClass.getClassLoader)
      val fingerprints = new ErrorTriggeringFramework().fingerprints()

      val result = TestRunnerUtils.matchFingerprints(
        cl,
        classOf[BrokenClass],
        fingerprints,
        isModule = false
      )
      assert(result.isEmpty)
    }

    test("matchFingerprints returns None on NoClassDefFoundError for annotations") {
      val cl = new BrokenReflectionClassLoader(getClass.getClassLoader)
      val fingerprints = new AnnotationErrorFramework().fingerprints()

      val result = TestRunnerUtils.matchFingerprints(
        cl,
        classOf[BrokenClass],
        fingerprints,
        isModule = false
      )
      assert(result.isEmpty)
    }
  }
}
