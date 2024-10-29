package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._
import os.Path
import mill.api.NonDeterministicFiles
import scala.jdk.CollectionConverters._

object ReproducibleOutTest extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("reproducible out folder") - integrationTest { tester =>
      import tester._

      val srcDir = os.pwd / "example" / "scalalib" / "web" / "5-webapp-scalajs-shared"
      val project1 = os.temp.dir()
      val project2 = os.temp.dir()

      // Copy source to two separate directories
      os.copy(srcDir, project1)
      os.copy(srcDir, project2)

      // Custom COURSIER_CACHE for project2
      val customCoursierCache = os.temp.dir()

      // Run Mill commands
      def runCommands(projectDir: Path): Unit = {
        eval(("runBackground"), cwd = projectDir)
        eval(("clean"), cwd = projectDir)
        eval(("runBackground"), cwd = projectDir)
        eval(("jar"), cwd = projectDir)
        eval(("assembly"), cwd = projectDir)
      }

      runCommands(project1)

      withCustomEnv(customCoursierCache) {
        runCommands(project2)
      }

      compareOutFolders(project1 / "out", project2 / "out")
    }
  }

  def compareOutFolders(dir1: Path, dir2: Path): Unit = {
    val files1 = os.walk(dir1).filter(os.isFile)
    val files2 = os.walk(dir2).filter(os.isFile)

    if (files1.size != files2.size) {
      throw new RuntimeException(
        s"Number of files in out folders don't match: ${files1.size} vs ${files2.size}"
      )
    }

    for ((file1, file2) <- files1.zip(files2)) {
      val relPath1 = file1.relativeTo(dir1)
      val relPath2 = file2.relativeTo(dir2)
      if (relPath1 != relPath2) {
        throw new RuntimeException(s"File paths don't match: $relPath1 vs $relPath2")
      }

      if (!NonDeterministicFiles.isNonDeterministic(file1)) {
        if (file1.ext == "zip" || file1.ext == "jar") {
          compareZipFiles(file1, file2)
        } else {
          val content1 = os.read.bytes(file1)
          val content2 = os.read.bytes(file2)
          if (!content1.sameElements(content2)) {
            throw new RuntimeException(s"File contents don't match for $relPath1")
          }
        }
      }
    }
  }

  def compareZipFiles(file1: Path, file2: Path): Unit = {
    val zin1 = new java.util.zip.ZipFile(file1.toIO)
    val zin2 = new java.util.zip.ZipFile(file2.toIO)

    try {
      val entries1 = zin1.entries().asScala.toList.sortBy(_.getName)
      val entries2 = zin2.entries().asScala.toList.sortBy(_.getName)

      if (entries1.size != entries2.size) {
        throw new RuntimeException(
          s"Number of entries in zip files don't match: ${entries1.size} vs ${entries2.size}"
        )
      }

      for ((entry1, entry2) <- entries1.zip(entries2)) {
        if (entry1.getName != entry2.getName) {
          throw new RuntimeException(
            s"Zip entry names don't match: ${entry1.getName} vs ${entry2.getName}"
          )
        }
        if (!entry1.isDirectory) {
          val content1 = zin1.getInputStream(entry1).readAllBytes()
          val content2 = zin2.getInputStream(entry2).readAllBytes()
          if (!content1.sameElements(content2)) {
            throw new RuntimeException(s"Zip entry contents don't match for ${entry1.getName}")
          }
        }
      }
    } finally {
      zin1.close()
      zin2.close()
    }
  }

  def withCustomEnv(customCoursierCache: Path)(f: => Unit): Unit = {
    val originalCoursierCache = sys.env.get("COURSIER_CACHE")
    val originalUserHome = sys.props("user.home")

    try {
      sys.props.put("COURSIER_CACHE", customCoursierCache.toString)
      sys.props.put("user.home", (customCoursierCache / "custom-home").toString)
      f
    } finally {
      sys.props.remove("COURSIER_CACHE")
      originalCoursierCache.foreach(v => sys.props.put("COURSIER_CACHE", v))
      sys.props.put("user.home", originalUserHome)
    }
  }
}
