package mill.integration

import utest._

object MillPluginClasspathTest extends IntegrationTestSuite {
  initWorkspace()

  val embeddedModules = Seq(
    ("com.lihaoyi", "mill-dev_2.13"),
    ("com.lihaoyi", "mill-main-client"),
    ("com.lihaoyi", "mill-main-api_2.13"),
    ("com.lihaoyi", "mill-main-util_2.13"),
    ("com.lihaoyi", "mill-main-codesig_2.13"),
    ("com.lihaoyi", "mill-runner-linenumbers_2.13"),
    ("com.lihaoyi", "mill-bsp_2.13"),
    ("com.lihaoyi", "mill-scalanativelib-worker-api_2.13"),
    ("com.lihaoyi", "mill-testrunner-entrypoint"),
    ("com.lihaoyi", "mill-scalalib-api_2.13"),
    ("com.lihaoyi", "mill-testrunner_2.13"),
    ("com.lihaoyi", "mill-main-define_2.13"),
    ("com.lihaoyi", "mill-main-resolve_2.13"),
    ("com.lihaoyi", "mill-main-eval_2.13"),
    ("com.lihaoyi", "mill-main_2.13"),
    ("com.lihaoyi", "mill-scalalib_2.13"),
    ("com.lihaoyi", "mill-scalanativelib_2.13"),
    ("com.lihaoyi", "mill-scalajslib-worker-api_2.13"),
    ("com.lihaoyi", "mill-scalajslib_2.13"),
    ("com.lihaoyi", "mill-runner_2.13"),
    ("com.lihaoyi", "mill-idea_2.13")
//    ("com.lihaoyi", "mainargs_2.13"),
//    ("org.codehaus.plexus", "plexus-utils"),
//    ("com.lihaoyi", "upack_2.13"),
//    ("org.codehaus.plexus", "plexus-container-default"),
//    ("com.lihaoyi", "sourcecode_2.13"),
//    ("org.virtuslab.scala-cli", "config_2.13"),
//    ("com.lihaoyi", "fastparse_2.13"),
//    ("com.eed3si9n.jarjarabrams", "jarjar-abrams-core_2.13"),
//    ("com.kohlschutter.junixsocket", "junixsocket-common"),
//    ("com.lihaoyi", "requests_2.13"),
//    ("org.scala-lang.modules", "scala-xml_2.13"),
//    ("org.codehaus.plexus", "plexus-archiver"),
//    ("io.get-coursier", "coursier-cache_2.13"),
//    ("com.eed3si9n.jarjar", "jarjar"),
//    ("com.lihaoyi", "ujson_2.13"),
//    ("org.apache.xbean", "xbean-reflect"),
//    ("com.lihaoyi", "upickle-implicits_2.13"),
//    ("org.scalameta", "scalafmt-dynamic_2.13"),
//    ("org.apache.commons", "commons-lang3"),
//    ("org.scalameta", "scalafmt-interfaces"),
//    ("com.lihaoyi", "upickle_2.13"),
//    ("io.get-coursier", "coursier-proxy-setup"),
//    ("net.java.dev.jna", "jna-platform"),
//    ("com.lihaoyi", "scalaparse_2.13"),
//    ("com.github.luben", "zstd-jni"),
//    ("org.apache.commons", "commons-compress"),
//    ("com.lihaoyi", "fansi_2.13"),
//    ("org.codehaus.plexus", "plexus-io"),
//    ("com.lihaoyi", "os-lib_2.13"),
//    ("org.scala-lang.modules", "scala-collection-compat_2.13"),
//    ("io.get-coursier", "coursier_2.13"),
//    ("org.tukaani", "xz"),
//    ("net.java.dev.jna", "jna"),
//    ("com.kohlschutter.junixsocket", "junixsocket-native-common"),
//    ("com.lihaoyi", "pprint_2.13"),
//    ("com.lihaoyi", "upickle-core_2.13"),
//    ("io.get-coursier.jniutils", "windows-jni-utils"),
//    ("javax.inject", "javax.inject"),
//    ("io.github.java-diff-utils", "java-diff-utils"),
//    ("io.get-coursier", "interface"),
//    ("org.scala-lang", "scala-compiler"),
//    ("org.scala-lang", "scala-library"),
//    ("io.get-coursier", "coursier-core_2.13"),
//    ("org.iq80.snappy", "snappy"),
//    ("org.fusesource.jansi", "jansi"),
//    ("org.jline", "jline"),
//    ("io.github.alexarchambault", "concurrent-reference-hash-map"),
//    ("commons-io", "commons-io"),
//    ("io.get-coursier", "coursier-util_2.13"),
//    ("org.scala-sbt", "test-interface"),
//    ("org.ow2.asm", "asm-tree"),
//    ("org.ow2.asm", "asm-commons"),
//    ("com.github.plokhotnyuk.jsoniter-scala", "jsoniter-scala-core_2.13"),
//    ("org.scala-lang", "scala-reflect"),
//    ("com.kohlschutter.junixsocket", "junixsocket-core"),
//    ("com.lihaoyi", "geny_2.13"),
//    ("com.typesafe", "config"),
//    ("org.codehaus.plexus", "plexus-classworlds"),
//    ("org.slf4j", "slf4j-api"),
//    ("io.github.alexarchambault.windows-ansi", "windows-ansi"),
//    ("org.ow2.asm", "asm"),
//    ("com.lihaoyi", "mill-moduledefs_2.13")
  )

  val tests: Tests = Tests {
    test("exclusions") - {
      val res1 = eval("--meta-level", "1", "resolveDepsExclusions")
      assert(res1)

      val exclusions = metaValue[Seq[(String, String)]]("mill-build.resolveDepsExclusions")

      // pprint.pprintln(exclusions)
      val expectedExclusions = embeddedModules

      val diff = expectedExclusions.toSet.diff(exclusions.toSet)
      assert(diff.isEmpty)

    }
    test("runClasspath") - {
      // We expect Mill core transitive dependencies to be filtered out
      val res1 = eval("--meta-level", "1", "runClasspath")
      assert(res1)

      val runClasspath = metaValue[Seq[String]]("mill-build.runClasspath")
//      pprint.pprintln(runClasspath)

      val unexpectedArtifacts = embeddedModules.map {
        case (o, n) => s"${o.replaceAll("[.]", "/")}/${n}"
      }
//      pprint.pprintln(unexpectedArtifacts)

      val unexpected = unexpectedArtifacts.flatMap { a =>
        runClasspath.find(p => p.toString.contains(a)).map((a, _))
      }.toMap
      assert(unexpected.isEmpty)

      val expected = Seq("com/disneystreaming/smithy4s/smithy4s-mill-codegen-plugin_mill0.11_2.13")
      assert(expected.forall(a => runClasspath.exists(p => p.toString().contains(a))))
    }

  }
}
