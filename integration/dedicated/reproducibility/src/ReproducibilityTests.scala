package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*

/**
 * Tests that Mill's `out/` folder is reproducible: building the same project in two different
 * locations (and under two different `$HOME`s) produces byte-for-byte identical `out/` contents,
 * and that those identical contents let a remote cache populated by one build fully satisfy a
 * second build elsewhere without recomputing anything.
 */
object ReproducibilityTests extends UtestIntegrationTestSuite {

  // Each block models a fresh checkout (empty `out/`); opt out of the `fast` flavor's shared
  // output dir so one build's local cache can't satisfy another.
  override def allowSharedOutputDir: Boolean = false

  /** The cacheable tasks we build; assembly pulls in `compile`, `allForkEnv` exercises env aliasing. */
  val appTasks: Seq[String] = Seq(
    "javaApp.assembly",
    "scalaApp.assembly",
    "kotlinApp.assembly",
    "kotlinIcApp.assembly",
    "javaApp.allForkEnv",
    "scalaApp.allForkEnv",
    "kotlinApp.allForkEnv",
    "kotlinIcApp.allForkEnv"
  )

  /** Join task selectors with `+` so they run in a single Mill invocation. */
  def plus(tasks: Seq[String]): Seq[String] = tasks.flatMap(Seq("+", _)).tail

  def normalize(workspacePath: os.Path): Unit = {
    for (p <- os.walk(workspacePath / "out")) {
      val sub = p.subRelativeTo(workspacePath).toString()

      // Kotlin IC's `last-build.bin` is a serialized wall-clock timestamp, not an input to any
      // task, so it can't be byte-reproducible and doesn't affect caching.
      val kotlinIcBuildTimestamp = p.last == "last-build.bin"

      val cacheable =
        (sub.contains(".dest") || sub.contains(".json") || os.isDir(p)) &&
          !kotlinIcBuildTimestamp &&
          !sub.replace("out/mill-build", "").contains("mill-") &&
          !(p.ext == "json" && ujson.read(
            os.read(p)
          ).objOpt.flatMap(_.get("value")).flatMap(_.objOpt).flatMap(_.get("worker")).nonEmpty)

      if (!cacheable) {
        os.remove.all(p)
      }
    }
  }

  // A symlink pointing at the real home, so the Mill subprocess sees a *different* `os.home` string
  // while still sharing the real coursier/ivy cache (no re-download). Building under two distinct
  // homes means any un-relativized home path leaks as differing bytes in the two `out/` folders.
  def symlinkedHome(): os.Path = {
    val link = os.temp.dir() / "home"
    os.symlink(link, os.home)
    link
  }

  def homeEnv(home: os.Path): Map[String, String] = Map(
    "HOME" -> home.toString,
    "USERPROFILE" -> home.toString,
    // Mill forwards `JAVA_OPTS` into the daemon JVM (see `MillProcessLauncher.computeJvmOpts`), so
    // this reliably drives `user.home`/`os.home` cross-platform, regardless of the OS `$HOME`.
    "JAVA_OPTS" -> s"-Duser.home=$home"
  )

  // Whether `task` was recomputed (`"cached": false` in `mill-profile.json`) rather than served
  // from a cache, in the most recent invocation in `tester`'s workspace.
  def evaluated(tester: IntegrationTester, task: String): Boolean = {
    val profile = os.read(tester.workspacePath / "out" / mill.constants.OutFiles.millProfile)
    ujson.read(profile).arr.exists { e =>
      e("label").str == task && e.obj.get("cached").flatMap(_.boolOpt).contains(false)
    }
  }

  def cachedInProfile(profilePath: os.Path, task: String): Boolean = {
    val profile = os.read(profilePath)
    ujson.read(profile).arr.exists { e =>
      e("label").str == task && e.obj.get("cached").flatMap(_.boolOpt).contains(true)
    }
  }

  def metaBuildCached(tester: IntegrationTester, task: String): Boolean =
    cachedInProfile(
      tester.workspacePath / "out" / "mill-build" / mill.constants.OutFiles.millProfile,
      task
    )

  def writeYamlJavaProject(workspacePath: os.Path): Unit = {
    os.list(workspacePath).filter(_.last != "out").foreach(os.remove.all(_))
    os.write(workspacePath / "build.mill.yaml", "", createFolders = true)
    os.write(
      workspacePath / "bar/package.mill.yaml",
      """extends: JavaModule
        |""".stripMargin,
      createFolders = true
    )
    os.write(
      workspacePath / "bar/src/bar/Bar.java",
      """package bar;
        |
        |public class Bar {
        |  public static String value() {
        |    return "bar";
        |  }
        |}
        |""".stripMargin,
      createFolders = true
    )
  }

  /** The real `bazel-remote` binary, downloaded once and cached by the `bazelRemote` build task. */
  def bazelRemoteBinary: os.Path = os.Path(sys.env("MILL_TEST_BAZEL_REMOTE"))

  /** Boot a real `bazel-remote` HTTP cache server on a free port, run `f` against it, then stop it. */
  def withBazelRemote[T](f: String => T): T = {
    val dataDir = os.temp.dir(prefix = "bazel-remote-data")
    val port = {
      val s = new java.net.ServerSocket(0)
      try s.getLocalPort
      finally s.close()
    }
    val proc = os
      .proc(
        bazelRemoteBinary,
        "--dir",
        dataDir,
        "--max_size",
        "1",
        "--http_address",
        s"localhost:$port"
      )
      .spawn(stdout = os.Inherit, stderr = os.Inherit)
    val url = s"http://localhost:$port"
    try {
      val deadline = System.currentTimeMillis() + 30000
      def ready(): Boolean =
        try {
          val conn = new java.net.URI(s"$url/status").toURL.openConnection()
            .asInstanceOf[java.net.HttpURLConnection]
          conn.setConnectTimeout(500)
          conn.setReadTimeout(500)
          try conn.getResponseCode == 200
          finally conn.disconnect()
        } catch { case _: Throwable => false }
      while (!ready() && System.currentTimeMillis() < deadline) Thread.sleep(200)
      if (!ready()) throw new RuntimeException("bazel-remote did not become ready in 30s")
      f(url)
    } finally {
      proc.destroy()
      proc.destroyForcibly()
    }
  }

  val tests: Tests = Tests {
    // Build the same project twice, in two different workspaces and under two different homes, then
    // assert the (normalized) `out/` folders are byte-for-byte identical.
    test("diff") - {
      def run(home: os.Path) = integrationTest { tester =>
        val env = homeEnv(home)
        tester.eval(("--meta-level", "1", "runClasspath"), env = env, check = true)
        tester.eval(plus(appTasks), env = env, check = true)
        val res = tester.eval(("show", "foo"), env = env, check = true)
        val lastNonEmptyLine =
          res.out.linesIterator.filter(_.nonEmpty).toSeq.lastOption.getOrElse("")
        assert(lastNonEmptyLine == "31337")
        tester.workspacePath
      }

      val workspacePath1 = run(symlinkedHome())
      val workspacePath2 = run(symlinkedHome())
      assert(workspacePath1 != workspacePath2)
      normalize(workspacePath1)
      normalize(workspacePath2)
      val diffStat = os.call(
        ("git", "diff", "--no-index", "--stat", workspacePath1, workspacePath2),
        check = false
      ).out.text()
      assert(diffStat.isEmpty)
    }

    // Populate a remote cache from one checkout, then build a fresh checkout elsewhere against the
    // same cache and assert the expensive tasks are served from it rather than rebuilt.
    test("remoteCache") - withBazelRemote { url =>
      val cacheArgs = Seq("--remote-cache-location", url)
      val compileTasks =
        Seq("javaApp.compile", "scalaApp.compile", "kotlinApp.compile", "kotlinIcApp.compile")

      // First checkout: cold cache, everything is computed locally and pushed.
      integrationTest { tester =>
        val res = tester.eval(cacheArgs ++ plus(appTasks), check = true)
        assert(res.isSuccess)
        for (t <- compileTasks) assert(evaluated(tester, t))
      }

      // Second checkout: warm cache, the compile/assembly work is served from it, not recomputed.
      integrationTest { tester =>
        val res = tester.eval(cacheArgs ++ plus(appTasks), check = true)
        assert(res.isSuccess)
        for (
          t <- compileTasks ++ Seq(
            "javaApp.assembly",
            "scalaApp.assembly",
            "kotlinApp.assembly",
            "kotlinIcApp.assembly"
          )
        )
          assert(!evaluated(tester, t))
      }
    }

    test("remoteCacheYamlBuildOverrides") - {
      val cache = os.temp.dir(prefix = "mill-yaml-remote-cache")
      val cacheArgs = Seq(
        "--remote-cache-location",
        cache.toString,
        "--remote-cache-filter",
        "millBuildRootModuleResult"
      )

      integrationTest { tester =>
        writeYamlJavaProject(tester.workspacePath)
        val res = tester.eval(cacheArgs ++ Seq("bar.compile"), check = true)
        assert(res.isSuccess)
        assert(evaluated(tester, "bar.compile"))
      }

      integrationTest { tester =>
        writeYamlJavaProject(tester.workspacePath)
        val res = tester.eval(cacheArgs ++ Seq("bar.compile"), check = true)
        assert(res.isSuccess)
        assert(metaBuildCached(tester, "millBuildRootModuleResult"))
      }
    }
  }
}
