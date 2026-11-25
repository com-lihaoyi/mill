package mill.javalib.zinc

import sbt.internal.inc.{Analysis, CompileOutput, LastModified, Relations}
import sbt.internal.inc.consistent.ConsistentFileAnalysisStore
import utest.*
import xsbti.VirtualFileRef
import xsbti.compile.analysis.ReadWriteMappers
import xsbti.compile.{AnalysisContents, CompileOrder, FileHash, MiniOptions, MiniSetup}

object ZincRootMappersTests extends TestSuite {
  def mappers(roots: (String, os.Path)*) = {
    val rootPaths = roots.map(_ -> _.toString)
    ZincRootMappers(rootPaths, ZincWorker.fileConverter(rootPaths))
  }

  def store(file: os.Path, mappers: ReadWriteMappers) =
    ConsistentFileAnalysisStore.binary(file.toIO, mappers, true, 1)

  val noOptions = MiniOptions.of(Array(), Array(), Array())
  def contents(analysis: Analysis, options: MiniOptions = noOptions) = AnalysisContents.create(
    analysis,
    MiniSetup.of(CompileOutput(os.temp.dir().toNIO), options, "", CompileOrder.Mixed, true, Array())
  )

  val tests: Tests = Tests {
    test("codec") {
      // Plain strings, so this runs the same on every OS. Least specific first: the most
      // specific root wins whatever the order
      val codec = ZincRootMappers.Codec(
        Seq("HOME" -> "/home/u", "WORKSPACE" -> "/home/u/ws", "MILL_OUT" -> "/home/u/ws/out")
      )
      for (
        (in, out) <- Seq(
          "/home/u/ws/out/foo/A.class" -> "${MILL_ROOT_MILL_OUT}/foo/A.class",
          "/home/u/ws" -> "${MILL_ROOT_WORKSPACE}",
          // Only whole paths match, also not in the middle of another path
          "/home/u/ws2/A.scala" -> "${MILL_ROOT_HOME}/ws2/A.scala",
          "/data/home/u/x" -> "/data/home/u/x",
          // Paths inside options, also after the `/` of `file://`
          "-Xplugin:/home/u/p.jar" -> "-Xplugin:${MILL_ROOT_HOME}/p.jar",
          "-cp=/home/u/ws/a.jar:/home/u/ws/out/b" ->
            "-cp=${MILL_ROOT_WORKSPACE}/a.jar:${MILL_ROOT_MILL_OUT}/b",
          "file:///home/u/ws/x" -> "file://${MILL_ROOT_WORKSPACE}/x"
        )
      ) assert(codec.encode(in) == out)
      // A literal `${HOME}` in an option stays as it is, so the options don't look changed
      assert(codec.decode("-Dx=${HOME}/a") == "-Dx=${HOME}/a")

      // On Windows, roots and the options Mill writes are `os.Path` strings, with `/`
      val win = ZincRootMappers.Codec(Seq("WORKSPACE" -> "C:/ws"))
      val cp = "-cp=C:/ws/out/a.jar;C:/b.jar"
      assert(win.encode(cp) == "-cp=${MILL_ROOT_WORKSPACE}/out/a.jar;C:/b.jar")
      assert(win.decode("${MILL_ROOT_WORKSPACE}/out/a.jar") == "C:/ws/out/a.jar")
    }

    // The analysis of a checkout is read in its copy, whose roots are elsewhere
    test("setupIsReadAtTheNewLocation") {
      val base = os.temp.dir()
      def mappersAt(ws: os.Path, home: os.Path) =
        mappers("MILL_OUT" -> ws / "out", "WORKSPACE" -> ws, "HOME" -> home)
      def optionsAt(ws: os.Path, home: os.Path) = MiniOptions.of(
        Array(FileHash.of((home / "x.jar").toNIO, 1)),
        Array(s"-Xplugin:${home / "p.jar"}"),
        Array("-s", (ws / "out/gen").toString)
      )
      val (home1, home2) = (base / "a", base / "c")
      val (ws1, ws2) = (home1 / "ws", base / "b/ws")
      val file = base / "zinc"
      store(file, mappersAt(ws1, home1)).set(contents(Analysis.empty, optionsAt(ws1, home1)))
      val read = store(file, mappersAt(ws2, home2)).get().get().getMiniSetup
      assert(read.options == optionsAt(ws2, home2))
    }

    test("olderAnalysesAreReadWithCurrentIds") {
      val ws = os.temp.dir()
      val lib = os.temp.dir() / "lib.jar"
      // As an older Mill wrote them: absolute paths with `/`, also on Windows
      def ref(p: os.Path) = VirtualFileRef.of(p.toString.replace('\\', '/'))
      val analysis = Analysis.empty.copy(relations =
        Relations.empty.addSource(
          src = ref(ws / "foo/src/A.java"),
          products = Seq(ref(ws / "out/foo/A.class")),
          classes = Nil,
          internalDeps = Nil,
          externalDeps = Nil,
          libraryDeps = Seq((ref(lib), "Lib", new LastModified(0)))
        )
      )
      val file = ws / "zinc"
      store(file, ReadWriteMappers.getEmptyMappers).set(contents(analysis))
      // Least specific first, unlike `MappedRoots`: the file converter sorts them too
      val relations = store(file, mappers("WORKSPACE" -> ws, "MILL_OUT" -> ws / "out")).get().get()
        .getAnalysis.asInstanceOf[Analysis].relations
      assert(relations.allSources.map(_.id) == Set("${WORKSPACE}/foo/src/A.java"))
      assert(relations.allProducts.map(_.id) == Set("${MILL_OUT}/foo/A.class"))
      // Outside the roots, a path keeps its absolute id
      assert(relations.allLibraryDeps.map(_.id) == Set(ref(lib).id))
    }
  }
}
