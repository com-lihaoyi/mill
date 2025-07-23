package mill.scalalib

import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import HelloWorldTests.*
import mill.api.Discover
import mill.util.TokenReaders._

object ScalaSemanticDbTests extends TestSuite {

  object SemanticWorld extends TestRootModule {
    object core extends SemanticModule

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("semanticDbData") {
      def semanticDbFiles: Set[os.SubPath] = Set(
        os.sub / "META-INF/semanticdb/core/src/Main.scala.semanticdb",
        os.sub / "META-INF/semanticdb/core/src/Result.scala.semanticdb",
        os.sub / "Main0.class",
        os.sub / "Main0$.class",
        os.sub / "Main.class",
        os.sub / "Main$.class",
        os.sub / "Person$.class",
        os.sub / "Person.class",
        os.sub / "Main$delayedInit$body.class"
      )

      test("fromScratch") - UnitTester(SemanticWorld, sourceRoot = resourcePath).scoped { eval =>
        {
          println("first - expected full compile")
          val Right(result) = eval.apply(SemanticWorld.core.semanticDbData): @unchecked

          val dataPath = eval.outPath / "core/semanticDbData.dest/data"
          val outputFiles =
            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))

          val expectedSemFiles = semanticDbFiles
          assert(
            result.value.path == dataPath,
            outputFiles.nonEmpty,
            outputFiles.toSet == expectedSemFiles,
            result.evalCount > 0,
            os.exists(dataPath / os.up / "zinc")
          )
        }
        {
          println("second - expected no compile")
          // don't recompile if nothing changed
          val Right(result2) = eval.apply(SemanticWorld.core.semanticDbData): @unchecked
          assert(result2.evalCount == 0)
        }
      }
      test("incremental") - UnitTester(
        SemanticWorld,
        sourceRoot = resourcePath,
        debugEnabled = true
      ).scoped { eval =>
        // create some more source file to have a reasonable low incremental change later
        val extraFiles = Seq("Second", "Third", "Fourth").map { f =>
          val file = eval.evaluator.workspace / "core/src/hello" / s"${f}.scala"
          os.write(
            file,
            s"""package hello
               |class ${f}
               |""".stripMargin,
            createFolders = true
          )
          val sem =
            os.sub / "META-INF/semanticdb/core/src/hello" / s"${f}.scala.semanticdb"
          (file, sem)
        }
//        val resultFile = eval.evaluator.workspace / "core/src/Result.scala"

        {
          println("first - expected full compile")
          val Right(result) = eval.apply(SemanticWorld.core.semanticDbData): @unchecked

          val dataPath = eval.outPath / "core/semanticDbData.dest/data"
          val outputFiles =
            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))

          val expectedSemFiles = semanticDbFiles.filter(_.ext != "class") ++ extraFiles.map(_._2)
          pprint.log(outputFiles.toSet.filter(_.ext != "class"))
          pprint.log(expectedSemFiles)
          assert(
            result.value.path == dataPath,
            outputFiles.toSet.filter(_.ext != "class") == expectedSemFiles,
            result.evalCount > 0
          )
        }
        // change nothing
        {
          println("second - expect no compile due to Mill caching")
          val Right(result2) = eval.apply(SemanticWorld.core.semanticDbData): @unchecked
          assert(result2.evalCount == 0)
        }

        // change one
        {
          println("third - expect inc compile of one file\n")
          os.write.append(extraFiles.head._1, "  ")

          val Right(result) = eval.apply(SemanticWorld.core.semanticDbData): @unchecked
          val outputFiles =
            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))
          val expectedFiles = semanticDbFiles.filter(_.ext != "class") ++ extraFiles.map(_._2)
          assert(
            outputFiles.toSet.filter(_.ext != "class") == expectedFiles,
            result.evalCount > 0
          )
        }
        // remove one
        {
          println("fourth - expect inc compile with one deleted file")
          os.remove(extraFiles.head._1)

          val Right(result) = eval.apply(SemanticWorld.core.semanticDbData): @unchecked
          val outputFiles =
            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))
          val expectedFiles =
            semanticDbFiles.filter(_.ext != "class") ++ extraFiles.map(_._2).drop(1)
          assert(
            outputFiles.toSet.filter(_.ext != "class") == expectedFiles,
            result.evalCount > 0
          )
        }
      }
    }

  }
}
