package mill.scalalib

import utest._

object ClassLoaderTests extends TestSuite {
  try {
    getClass().getClassLoader().loadClass("com.sun.nio.zipfs.ZipFileSystemProvider")
  } catch {
    case _: ClassNotFoundException if !System.getProperty("java.specification.version").startsWith("1.") =>
      // Don't fail on Java 9+
  }
  val tests = Tests {}
}
