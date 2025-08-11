package mill.javalib

import utest._

object ClassLoaderTests extends TestSuite {
  val tests = Tests {
    val isJava8 = System.getProperty("java.specification.version").startsWith("1.")
    test("com.sun classes exist in tests classpath (Java 8 only)") {
      if (isJava8) {
        getClass().getClassLoader().loadClass("com.sun.nio.zipfs.ZipFileSystemProvider")
      }
    }
  }
}
