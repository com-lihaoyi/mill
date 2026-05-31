package mill.integration

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import mill.testkit.UtestIntegrationTestSuite
import utest.*

import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Integration tests for remote caching, adapted from the manual steps in
 * https://github.com/com-lihaoyi/mill/pull/2777. Instead of a real `bazel-remote` server they
 * use a tiny in-process HTTP cache (`PUT`/`GET` on `/ac` and `/cas`) backed by a temp dir, and
 * each `integrationTest{}` block is a fresh checkout sharing outputs through it.
 */
object RemoteCacheTests extends UtestIntegrationTestSuite {

  // Each `integrationTest` block gets its own temp `run-N` workspace, copies these test
  // resources into it, and starts with no `out/` folder. Opt out of the `fast` flavor's shared
  // output dir too, otherwise one block's local cache would satisfy a later one.
  override def allowSharedOutputDir: Boolean = false

  def withServer[T](f: String => T): T = {
    val dir = os.temp.dir(prefix = "mill-remote-cache-server")
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      new HttpHandler {
        def handle(exchange: HttpExchange): Unit = {
          try {
            val rel = exchange.getRequestURI.getPath.stripPrefix("/")
            val file = dir / os.SubPath(rel)
            exchange.getRequestMethod match {
              case "PUT" =>
                val bytes = exchange.getRequestBody.readAllBytes()
                os.write.over(file, bytes, createFolders = true)
                exchange.sendResponseHeaders(200, -1)
              case "GET" if os.exists(file) =>
                val bytes = os.read.bytes(file)
                exchange.sendResponseHeaders(200, bytes.length.toLong)
                exchange.getResponseBody.write(bytes)
              case "HEAD" if os.exists(file) =>
                exchange.sendResponseHeaders(200, -1)
              case "GET" | "HEAD" =>
                exchange.sendResponseHeaders(404, -1)
              case _ =>
                exchange.sendResponseHeaders(405, -1)
            }
          } catch {
            case _: Throwable => exchange.sendResponseHeaders(500, -1)
          } finally exchange.close()
        }
      }
    )
    server.setExecutor(Executors.newFixedThreadPool(4))
    server.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}")
    finally server.stop(0)
  }

  def profileCached(tester: mill.testkit.IntegrationTester, task: String): Option[Boolean] = {
    val profile = os.read(tester.workspacePath / "out" / mill.constants.OutFiles.millProfile)
    val entries = ujson.read(profile).arr.filter(_("label").str == task)
    assert(entries.nonEmpty)
    entries.last.obj.get("cached") match {
      case Some(ujson.True) => Some(true)
      case Some(ujson.False) => Some(false)
      case Some(ujson.Null) | None => None
      case other => sys.error(s"Unexpected cached value for $task: $other")
    }
  }

  // Whether `task` was recomputed (`"cached": false` in `mill-profile.json`) rather than served
  // from a cache, in the most recent invocation in `tester`'s workspace.
  def evaluated(tester: mill.testkit.IntegrationTester, task: String): Boolean =
    profileCached(tester, task).contains(false)

  def assertProfileCached(
      tester: mill.testkit.IntegrationTester,
      expected: (String, Option[Boolean])*
  ): Unit = {
    for ((task, cached) <- expected) assert(profileCached(tester, task) == cached)
  }

  val tests: Tests = Tests {

    test("crossDirectorySharing") - withServer { url =>
      integrationTest { tester =>
        val res = tester.eval(("--remote-cache-location", url, "show", "cachedTask"))
        assert(res.isSuccess)
        assert(evaluated(tester, "cachedTask"))
        assert(res.out.contains("cachedTask-value"))
      }
      // Second checkout: served from the cache, so `cachedTask` is not re-evaluated.
      integrationTest { tester =>
        val res = tester.eval(("--remote-cache-location", url, "show", "cachedTask"))
        assert(res.isSuccess)
        assert(res.out.contains("cachedTask-value"))
        assert(!evaluated(tester, "cachedTask"))
      }
    }

    // A hit must restore the referenced `dest/` files, or `PathRef` re-validation forces a recompute.
    test("pathRefRestore") - withServer { url =>
      integrationTest { tester =>
        val res = tester.eval(("--remote-cache-location", url, "show", "cachedFile"))
        assert(res.isSuccess)
        assert(evaluated(tester, "cachedFile"))
      }
      integrationTest { tester =>
        val res = tester.eval(("--remote-cache-location", url, "show", "cachedFile"))
        assert(res.isSuccess)
        assert(!evaluated(tester, "cachedFile"))
        val restored = tester.workspacePath / "out/cachedFile.dest/data.txt"
        assert(os.exists(restored))
        assert(os.read(restored) == "file-contents-12345")
      }
    }

    // `--remote-cache-filter` limits caching to matching tasks; `uncachedTask` is always recomputed.
    test("filter") - withServer { url =>
      def filterArgs =
        Seq("--remote-cache-location", url, "--remote-cache-filter", "cachedTask")
      integrationTest { tester =>
        val res = tester.eval(filterArgs ++ Seq("cachedTask", "+", "uncachedTask"))
        assert(res.isSuccess)
        assert(evaluated(tester, "cachedTask"))
        assert(evaluated(tester, "uncachedTask"))
      }
      integrationTest { tester =>
        val res = tester.eval(filterArgs ++ Seq("cachedTask", "+", "uncachedTask"))
        assert(res.isSuccess)
        assert(!evaluated(tester, "cachedTask"))
        assert(evaluated(tester, "uncachedTask"))
      }
    }

    // Persistent tasks (e.g. `compile`) participate too — the primary use case.
    test("persistentTaskIsCached") - withServer { url =>
      integrationTest { tester =>
        val res = tester.eval(("--remote-cache-location", url, "persistentTask"))
        assert(res.isSuccess)
        assert(evaluated(tester, "persistentTask"))
      }
      integrationTest { tester =>
        val res = tester.eval(("--remote-cache-location", url, "persistentTask"))
        assert(res.isSuccess)
        assert(!evaluated(tester, "persistentTask")) // served from the remote cache
      }
    }

    test("onlyCacheableTaskTypesParticipate") - withServer { url =>
      def runGraph(tester: mill.testkit.IntegrationTester) = {
        val res = tester.eval(("--remote-cache-location", url, "graphCommand"))
        assert(res.isSuccess)
        assert(res.out.contains("graphCommand:"))
      }

      integrationTest { tester =>
        runGraph(tester)
        assertProfileCached(
          tester,
          "graphStableCached" -> Some(false),
          "graphInput" -> None,
          "graphSource" -> None,
          "graphCachedFromInput" -> Some(false),
          "graphCachedFromSource" -> Some(false),
          "graphPersistentFromInput" -> Some(false),
          "graphWorker" -> Some(false),
          "graphCachedFromWorker" -> Some(false),
          "graphCommand" -> Some(false)
        )
      }

      integrationTest { tester =>
        runGraph(tester)
        assertProfileCached(
          tester,
          "graphStableCached" -> Some(true),
          "graphInput" -> None,
          "graphSource" -> None,
          "graphCachedFromInput" -> Some(true),
          "graphCachedFromSource" -> Some(true),
          "graphPersistentFromInput" -> Some(true),
          "graphWorker" -> Some(false),
          "graphCachedFromWorker" -> Some(true),
          "graphCommand" -> Some(false)
        )
      }

      integrationTest { tester =>
        os.write.over(tester.workspacePath / "graph-input.txt", "changed")
        runGraph(tester)
        assertProfileCached(
          tester,
          "graphStableCached" -> Some(true),
          "graphInput" -> None,
          "graphSource" -> None,
          "graphCachedFromInput" -> Some(false),
          "graphCachedFromSource" -> Some(true),
          "graphPersistentFromInput" -> Some(false),
          "graphWorker" -> Some(false),
          "graphCachedFromWorker" -> Some(false),
          "graphCommand" -> Some(false)
        )
      }

      integrationTest { tester =>
        os.write.over(tester.workspacePath / "graph-source.txt", "changed-source")
        runGraph(tester)
        assertProfileCached(
          tester,
          "graphStableCached" -> Some(true),
          "graphInput" -> None,
          "graphSource" -> None,
          "graphCachedFromInput" -> Some(true),
          "graphCachedFromSource" -> Some(false),
          "graphPersistentFromInput" -> Some(true),
          "graphWorker" -> Some(false),
          "graphCachedFromWorker" -> Some(true),
          "graphCommand" -> Some(false)
        )
      }
    }

    // A `file:` URL uses a plain directory as the cache, with no server.
    test("localFolderBackend") - {
      val cacheDir = os.temp.dir(prefix = "mill-remote-cache-folder")
      val url = cacheDir.toNIO.toUri.toString
      integrationTest { tester =>
        val res = tester.eval(("--remote-cache-location", url, "show", "cachedTask"))
        assert(res.isSuccess)
        assert(evaluated(tester, "cachedTask"))
        assert(res.out.contains("cachedTask-value"))
      }
      assert(os.exists(cacheDir / "ac") && os.exists(cacheDir / "cas"))
      integrationTest { tester =>
        val res = tester.eval(("--remote-cache-location", url, "show", "cachedTask"))
        assert(res.isSuccess)
        assert(res.out.contains("cachedTask-value"))
        assert(!evaluated(tester, "cachedTask"))
      }
    }

    // An unreachable cache degrades gracefully: the build still succeeds, computing locally.
    test("gracefulDegradationWhenCacheUnreachable") - integrationTest { tester =>
      val res =
        tester.eval(("--remote-cache-location", "http://127.0.0.1:1", "show", "cachedTask"))
      assert(res.isSuccess)
      assert(evaluated(tester, "cachedTask"))
      assert(res.out.contains("cachedTask-value"))
    }

    // A hit must wipe stale `dest/` from a prior different-`inputsHash` build before unpacking.
    test("staleDestWipedOnRemoteHit") - withServer { url =>
      integrationTest { tester =>
        assert(tester.eval(("--remote-cache-location", url, "cachedFile")).isSuccess)
      }
      integrationTest { tester =>
        import tester.*
        os.write(workspacePath / "out/cachedFile.dest/STALE.txt", "stale", createFolders = true)
        val res = eval(("--remote-cache-location", url, "cachedFile"))
        assert(res.isSuccess)
        assert(!evaluated(tester, "cachedFile"))
        assert(os.exists(workspacePath / "out/cachedFile.dest/data.txt"))
        assert(!os.exists(workspacePath / "out/cachedFile.dest/STALE.txt"))
      }
    }

    // Different `--remote-cache-salt` values partition the cache into non-shared entries.
    test("salt") - withServer { url =>
      integrationTest { tester =>
        val res =
          tester.eval((
            "--remote-cache-location",
            url,
            "--remote-cache-salt",
            "saltA",
            "cachedTask"
          ))
        assert(res.isSuccess)
        assert(evaluated(tester, "cachedTask"))
      }
      integrationTest { tester =>
        val res =
          tester.eval((
            "--remote-cache-location",
            url,
            "--remote-cache-salt",
            "saltB",
            "cachedTask"
          ))
        assert(res.isSuccess)
        assert(evaluated(tester, "cachedTask")) // different salt → miss → recompute
      }
      integrationTest { tester =>
        val res =
          tester.eval((
            "--remote-cache-location",
            url,
            "--remote-cache-salt",
            "saltA",
            "cachedTask"
          ))
        assert(res.isSuccess)
        assert(!evaluated(tester, "cachedTask"))
      }
    }
  }
}
