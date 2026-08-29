package mill.graphviz

import utest.*

import javax.imageio.ImageIO

object VisualizeWorkerMainTests extends TestSuite {

  def tests: Tests = Tests {
    test("standaloneNode") {
      val dest = render(
        ujson.Obj(
          "tasks" -> ujson.Arr("standalone"),
          "edges" -> ujson.Arr(ujson.Obj("src" -> "standalone", "dests" -> ujson.Arr()))
        )
      )

      val outputNames = os.list(dest).map(_.last).sorted
      val requiredOutputNames = Seq("out.dot", "out.json", "out.svg", "out.txt")
      assert(
        outputNames == requiredOutputNames || outputNames == (requiredOutputNames :+ "out.png").sorted
      )
      assert(os.read(dest / "out.dot").contains("\"standalone\""))
      assert(os.read(dest / "out.json").contains("\"name\": \"standalone\""))
      assert(os.read(dest / "out.svg").contains("standalone"))
      assert(os.read(dest / "out.txt").contains("node standalone"))

      if (os.exists(dest / "out.png")) {
        val png = ImageIO.read((dest / "out.png").toIO)
        assert(png != null)
        assert(png.getWidth > 8, png.getHeight > 8)
      }
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
        val destinations = Seq(os.temp.dir(), os.temp.dir())
        val missingFontConfig = destinations.head / "missing-fontconfig.conf"
        val java = os.Path(sys.props("java.home")) / "bin" / "java"
        val result = os
          .proc(
            java,
            s"-Dsun.awt.fontconfig=$missingFontConfig",
            "-cp",
            sys.props("java.class.path"),
            "mill.graphviz.GraphvizTools",
            s"$graphFile;${destinations.head};txt,dot,json,svg,png",
            s"$graphFile;${destinations.last};txt,dot,json,svg,png"
          )
          .call(
            check = false,
            stdout = os.Pipe,
            stderr = os.Pipe,
            env = Map(
              "FONTCONFIG_FILE" -> missingFontConfig.toString,
              "FONTCONFIG_PATH" -> destinations.head.toString
            )
          )

        assert(result.exitCode == 0)
        assert(destinations.forall(dest => os.size(dest / "out.svg") > 0))
        assert(destinations.forall(dest => !os.exists(dest / "out.png")))
        assert(result.err.text().contains("The SVG and text graph outputs are still available"))
      }
    }
  }

  private def render(payload: ujson.Value): os.Path = {
    val payloadPath = os.temp(payload.render())
    val dest = os.temp.dir()
    VisualizeWorkerMain.main(Array(payloadPath.toString, dest.toString))
    dest
  }
}
