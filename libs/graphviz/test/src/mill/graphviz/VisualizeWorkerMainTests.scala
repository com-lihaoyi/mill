package mill.graphviz

import utest.*

import javax.imageio.ImageIO
import java.util.concurrent.TimeUnit

object VisualizeWorkerMainTests extends TestSuite {

  def tests: Tests = Tests {
    test("standaloneNode") {
      val dest = render(
        ujson.Obj(
          "tasks" -> ujson.Arr("standalone"),
          "edges" -> ujson.Arr(ujson.Obj("src" -> "standalone", "dests" -> ujson.Arr()))
        )
      )

      assert(os.read(dest / "out.dot").contains("\"standalone\""))
      assert(os.read(dest / "out.json").contains("\"name\": \"standalone\""))
      assert(os.read(dest / "out.svg").contains("standalone"))
      assert(os.read(dest / "out.txt").contains("node standalone"))
    }

    test("transitiveReduction") {
      val dest = render(
        ujson.Obj(
          "tasks" -> ujson.Arr("root", "middle", "leaf"),
          "edges" -> ujson.Arr(
            ujson.Obj("src" -> "root", "dests" -> ujson.Arr("middle", "leaf")),
            ujson.Obj("src" -> "middle", "dests" -> ujson.Arr("leaf"))
          )
        )
      )
      val dot = os.read(dest / "out.dot")

      assert(dot.contains("\"leaf\" -> \"middle\""))
      assert(dot.contains("\"middle\" -> \"root\""))
      assert(!dot.contains("\"leaf\" -> \"root\""))
    }

    test("missingFontConfig") {
      if (scala.util.Properties.isLinux) {
        val graphFile = os.temp("digraph { standalone }")
        val dest = os.temp.dir()
        val missingFontConfig = dest / "missing-fontconfig.conf"
        val java = os.Path(sys.props("java.home")) / "bin" / "java"
        val stdout = os.temp()
        val stderr = os.temp()
        val process = new ProcessBuilder(
          java.toString,
          s"-Dsun.awt.fontconfig=$missingFontConfig",
          "-cp",
          sys.props("java.class.path"),
          "mill.graphviz.GraphvizTools",
          s"$graphFile;$dest;txt,dot,json,svg,png"
        )
          .redirectOutput(stdout.toIO)
          .redirectError(stderr.toIO)
        process.environment().put("FONTCONFIG_FILE", missingFontConfig.toString)
        process.environment().put("FONTCONFIG_PATH", dest.toString)

        val running = process.start()
        val completed =
          try running.waitFor(20, TimeUnit.SECONDS)
          finally if (running.isAlive) running.destroyForcibly()

        assert(completed)
        assert(running.exitValue() != 0)
        assert(Seq("txt", "dot", "json", "svg").forall(ext => os.size(dest / s"out.$ext") > 0))
        assert(!os.exists(dest / "out.png"))
        assert(
          os.read(stderr).contains(
            "Install fontconfig and at least one system font, then retry."
          )
        )
      }
    }
  }

  private def render(payload: ujson.Value): os.Path = {
    val payloadPath = os.temp(payload.render())
    val dest = os.temp.dir()
    VisualizeWorkerMain.main(Array(payloadPath.toString, dest.toString))

    assert(
      os.list(dest).map(_.last).sorted ==
        Seq("out.dot", "out.json", "out.png", "out.svg", "out.txt")
    )
    val png = ImageIO.read((dest / "out.png").toIO)
    assert(png != null)
    assert(png.getWidth > 8, png.getHeight > 8)
    dest
  }
}
