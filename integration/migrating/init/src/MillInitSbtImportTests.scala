package mill.integration

import mill.integration.IntegrationTesterUtil.*
import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtImportTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test("fs2") - integrationTestGitRepo(
      "https://github.com/typelevel/fs2.git",
      "v3.12.0"
    ) { tester =>
      import tester.*

      assert(eval("init").isSuccess)

      val mvnDeps = showNamedMvnDeps(tester)
      assertGoldenLiteral(
        mvnDeps,
        Map(
          "mdoc[2.13.16].mvnDeps" -> List("org.scalameta::mdoc:2.6.2"),
          "protocols.native[2.13.16].mvnDeps" -> List(),
          "scodec.js[3.3.5].mvnDeps" -> List(),
          "io.native[3.3.5].mvnDeps" -> List("com.comcast::ip4s-core::3.6.0"),
          "reactive-streams[3.3.5].test.mvnDeps" -> List(
            "org.reactivestreams:reactive-streams-tck:1.0.4"
          ),
          "scodec.jvm[3.3.5].mvnDeps" -> List(),
          "protocols.js[3.3.5].mvnDeps" -> List(),
          "reactive-streams[2.13.16].mvnDeps" -> List(
            "org.reactivestreams:reactive-streams:1.0.4"
          ),
          "scodec.js[2.12.20].mvnDeps" -> List(),
          "mdoc[2.12.20].mvnDeps" -> List("org.scalameta::mdoc:2.6.2"),
          "io.jvm[3.3.5].mvnDeps" -> List("com.comcast::ip4s-core:3.6.0"),
          "core.jvm[2.13.16].test.mvnDeps" -> List(
            "org.typelevel::cats-effect-laws:3.6.0",
            "org.typelevel::cats-effect-testkit:3.6.0",
            "org.typelevel::cats-laws:2.11.0",
            "org.typelevel::discipline-munit:2.0.0-M3",
            "org.typelevel::munit-cats-effect:2.0.0",
            "org.typelevel::scalacheck-effect-munit:2.0.0-M2",
            "org.reactivestreams:reactive-streams-tck-flow:1.0.4"
          ),
          "scodec.native[2.12.20].mvnDeps" -> List(),
          "unidocs[2.12.20].mvnDeps" -> List(),
          "core.native[2.12.20].mvnDeps" -> List(
            "org.scodec::scodec-bits::1.1.38",
            "org.typelevel::cats-core::2.11.0",
            "org.typelevel::cats-effect::3.6.0"
          ),
          "reactive-streams[3.3.5].mvnDeps" -> List("org.reactivestreams:reactive-streams:1.0.4"),
          "protocols.js[2.12.20].mvnDeps" -> List(),
          "scodec.native[2.13.16].mvnDeps" -> List(),
          "protocols.native[2.12.20].mvnDeps" -> List(),
          "mdoc[3.3.5].mvnDeps" -> List("org.scalameta::mdoc:2.6.2"),
          "scodec.jvm[2.12.20].mvnDeps" -> List(),
          "reactive-streams[2.12.20].test.mvnDeps" -> List(
            "org.reactivestreams:reactive-streams-tck:1.0.4"
          ),
          "io.js[2.13.16].mvnDeps" -> List("com.comcast::ip4s-core::3.6.0"),
          "protocols.jvm[2.13.16].mvnDeps" -> List(),
          "core.js[2.12.20].mvnDeps" -> List(
            "org.scodec::scodec-bits::1.1.38",
            "org.typelevel::cats-core::2.11.0",
            "org.typelevel::cats-effect::3.6.0"
          ),
          "protocols.jvm[2.12.20].mvnDeps" -> List(),
          "scodec.js[2.13.16].mvnDeps" -> List(),
          "integration[2.13.16].mvnDeps" -> List(),
          "core.js[3.3.5].mvnDeps" -> List(
            "org.scodec::scodec-bits::1.1.38",
            "org.typelevel::cats-core::2.11.0",
            "org.typelevel::cats-effect::3.6.0"
          ),
          "core.native[3.3.5].mvnDeps" -> List(
            "org.scodec::scodec-bits::1.1.38",
            "org.typelevel::cats-core::2.11.0",
            "org.typelevel::cats-effect::3.6.0"
          ),
          "benchmark[2.12.20].mvnDeps" -> List(
            "org.openjdk.jmh:jmh-core:1.37",
            "org.openjdk.jmh:jmh-generator-bytecode:1.37",
            "org.openjdk.jmh:jmh-generator-reflection:1.37"
          ),
          "core.native[2.13.16].mvnDeps" -> List(
            "org.scodec::scodec-bits::1.1.38",
            "org.typelevel::cats-core::2.11.0",
            "org.typelevel::cats-effect::3.6.0"
          ),
          "protocols.jvm[3.3.5].mvnDeps" -> List(),
          "io.jvm[2.13.16].mvnDeps" -> List("com.comcast::ip4s-core:3.6.0"),
          "protocols.native[3.3.5].mvnDeps" -> List(),
          "reactive-streams[2.12.20].mvnDeps" -> List(
            "org.reactivestreams:reactive-streams:1.0.4"
          ),
          "benchmark[3.3.5].mvnDeps" -> List(
            "org.openjdk.jmh:jmh-core:1.37",
            "org.openjdk.jmh:jmh-generator-bytecode:1.37",
            "org.openjdk.jmh:jmh-generator-reflection:1.37"
          ),
          "core.jvm[2.12.20].mvnDeps" -> List(
            "org.scodec::scodec-bits:1.1.38",
            "org.typelevel::cats-core:2.11.0",
            "org.typelevel::cats-effect:3.6.0"
          ),
          "core.jvm[2.12.20].test.mvnDeps" -> List(
            "org.typelevel::cats-effect-laws:3.6.0",
            "org.typelevel::cats-effect-testkit:3.6.0",
            "org.typelevel::cats-laws:2.11.0",
            "org.typelevel::discipline-munit:2.0.0-M3",
            "org.typelevel::munit-cats-effect:2.0.0",
            "org.typelevel::scalacheck-effect-munit:2.0.0-M2",
            "org.reactivestreams:reactive-streams-tck-flow:1.0.4"
          ),
          "core.jvm[3.3.5].test.mvnDeps" -> List(
            "org.typelevel::cats-effect-laws:3.6.0",
            "org.typelevel::cats-effect-testkit:3.6.0",
            "org.typelevel::cats-laws:2.11.0",
            "org.typelevel::discipline-munit:2.0.0-M3",
            "org.typelevel::munit-cats-effect:2.0.0",
            "org.typelevel::scalacheck-effect-munit:2.0.0-M2",
            "org.reactivestreams:reactive-streams-tck-flow:1.0.4"
          ),
          "core.jvm[2.13.16].mvnDeps" -> List(
            "org.scodec::scodec-bits:1.1.38",
            "org.typelevel::cats-core:2.11.0",
            "org.typelevel::cats-effect:3.6.0"
          ),
          "core.jvm[3.3.5].mvnDeps" -> List(
            "org.scodec::scodec-bits:1.1.38",
            "org.typelevel::cats-core:2.11.0",
            "org.typelevel::cats-effect:3.6.0"
          ),
          "io.jvm[2.12.20].mvnDeps" -> List("com.comcast::ip4s-core:3.6.0"),
          "io.js[2.12.20].mvnDeps" -> List("com.comcast::ip4s-core::3.6.0"),
          "integration[3.3.5].mvnDeps" -> List(),
          "protocols.js[2.13.16].mvnDeps" -> List(),
          "benchmark[2.13.16].mvnDeps" -> List(
            "org.openjdk.jmh:jmh-core:1.37",
            "org.openjdk.jmh:jmh-generator-bytecode:1.37",
            "org.openjdk.jmh:jmh-generator-reflection:1.37"
          ),
          "io.native[2.12.20].mvnDeps" -> List("com.comcast::ip4s-core::3.6.0"),
          "unidocs[3.3.5].mvnDeps" -> List(),
          "scodec.jvm[2.13.16].mvnDeps" -> List(),
          "core.js[2.13.16].mvnDeps" -> List(
            "org.scodec::scodec-bits::1.1.38",
            "org.typelevel::cats-core::2.11.0",
            "org.typelevel::cats-effect::3.6.0"
          ),
          "scodec.native[3.3.5].mvnDeps" -> List(),
          "io.js[3.3.5].mvnDeps" -> List("com.comcast::ip4s-core::3.6.0"),
          "io.native[2.13.16].mvnDeps" -> List("com.comcast::ip4s-core::3.6.0"),
          "integration[2.12.20].mvnDeps" -> List(),
          "reactive-streams[2.13.16].test.mvnDeps" -> List(
            "org.reactivestreams:reactive-streams-tck:1.0.4"
          ),
          "unidocs[2.13.16].mvnDeps" -> List()
        )
      )
      val compileMvnDeps = showNamedCompileMvnDeps(tester)
      assertGoldenLiteral(
        compileMvnDeps,
        ()
      )
      val runMvnDeps = showNamedRunMvnDeps(tester)
      assertGoldenLiteral(
        runMvnDeps,
        Map(
          "example.runMvnDeps" -> List(),
          "lib.runMvnDeps" -> List(),
          "lib.test.runMvnDeps" -> List()
        )
      )
      val bomMvnDeps = showNamedBomMvnDeps(tester)
      assertGoldenLiteral(
        bomMvnDeps,
        Map(
          "example.bomMvnDeps" -> List(),
          "lib.bomMvnDeps" -> List(),
          "lib.test.bomMvnDeps" -> List()
        )
      )
      val javacOptions = showNamedJavacOptions(tester)
      assertGoldenLiteral(
        javacOptions,
        ()
      )

      val coreCompileRes = eval("core.__.compile")
      val coreCompileOut =
        coreCompileRes.out.linesIterator.map(_.replace(workspacePath.toString, "")).toSeq
      assert(
        coreCompileRes.isSuccess,
        coreCompileOut.exists(_.endsWith(
          "compiling 50 Scala sources to /fs2/out/core/js/2.13.16/compile.dest/classes ..."
        )),
        coreCompileOut.exists(_.endsWith(
          "compiling 50 Scala sources to /fs2/out/core/js/3.3.5/compile.dest/classes ..."
        )),
        coreCompileOut.exists(_.endsWith(
          "compiling 52 Scala sources to /fs2/out/core/jvm/2.13.16/compile.dest/classes ..."
        )),
        coreCompileOut.exists(_.endsWith(
          "compiling 52 Scala sources to /fs2/out/core/jvm/3.3.5/compile.dest/classes ..."
        )),
        coreCompileOut.exists(_.endsWith(
          "compiling 50 Scala sources to /fs2/out/core/native/2.13.16/compile.dest/classes ..."
        )),
        coreCompileOut.exists(_.endsWith(
          "compiling 50 Scala sources to /fs2/out/core/native/3.3.5/compile.dest/classes ..."
        ))
      )
    }
  }
}
