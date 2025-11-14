package mill.testkit

enum Chunk derives upickle.default.ReadWriter {
  case Yaml(lines: Seq[String])
  case Example(lines: Seq[String])
  case See(path: String, lines: Seq[String])
  case Scala(lines: Seq[String])
  case Comment(lines: Seq[String])
}

object ExampleParser {
  def apply(testRepoRoot: os.Path): Seq[Chunk] = {
    val result = collection.mutable.Buffer[Chunk]()

    def appendLine(line: String): Unit = {
      if (result.nonEmpty) {
        val last = result.last
        val updated = last match {
          case Chunk.Yaml(lines) => Chunk.Yaml(lines :+ line)
          case Chunk.Example(lines) => Chunk.Example(lines :+ line)
          case Chunk.See(path, lines) => Chunk.See(path, lines :+ line)
          case Chunk.Scala(lines) => Chunk.Scala(lines :+ line)
          case Chunk.Comment(lines) => Chunk.Comment(lines :+ line)
        }
        result(result.length - 1) = updated
      }
    }

    val rootBuildFileNames = Seq("build.mill")
    val buildFile = rootBuildFileNames.map(testRepoRoot / _)
      .find(os.exists)
      .getOrElse(
        sys.error(
          s"No build file named ${rootBuildFileNames.mkString("/")} found in $testRepoRoot"
        )
      )

    for (line <- os.read.lines(buildFile)) {
      val (newChunkType, lineToAdd) = line match {
        case s"/** Usage" => (Chunk.Example(Vector()), None)
        case s"/** See Also: $path */" =>
          (Chunk.See(path, Vector()), Some(os.read(os.Path(path, testRepoRoot))))
        case s"*/" => (Chunk.Scala(Vector()), None)
        case line @ s"//|$_" if result.nonEmpty && result.last.isInstanceOf[Chunk.Yaml] =>
          (Chunk.Yaml(Vector()), Some(line))
        case s"//$rest" if !rest.startsWith("|") =>
          (Chunk.Comment(Vector()), Some(rest.stripPrefix(" ")))
        case l =>
          val chunkType = if (result.nonEmpty) {
            result.last match {
              case _: Chunk.Comment | _: Chunk.Yaml => Chunk.Scala(Vector())
              case other => other
            }
          } else Chunk.Yaml(Vector()) // initial state
          (chunkType, Some(l))
      }

      if (result.isEmpty || newChunkType.getClass != result.last.getClass) {
        result.append(newChunkType)
      }

      lineToAdd.foreach(appendLine)
    }

    result.filter {
      case Chunk.Yaml(lines) => lines.mkString("\n").trim.nonEmpty
      case Chunk.Example(lines) => lines.mkString("\n").trim.nonEmpty
      case Chunk.See(_, lines) => lines.mkString("\n").trim.nonEmpty
      case Chunk.Scala(lines) => lines.mkString("\n").trim.nonEmpty
      case Chunk.Comment(lines) => lines.mkString("\n").trim.nonEmpty
    }.map {
      case Chunk.Yaml(lines) => Chunk.Yaml(lines.mkString("\n").trim.linesIterator.toVector)
      case Chunk.Example(lines) => Chunk.Example(lines.mkString("\n").trim.linesIterator.toVector)
      case Chunk.See(path, lines) =>
        Chunk.See(path, lines.mkString("\n").trim.linesIterator.toVector)
      case Chunk.Scala(lines) => Chunk.Scala(lines.mkString("\n").trim.linesIterator.toVector)
      case Chunk.Comment(lines) => Chunk.Comment(lines.mkString("\n").trim.linesIterator.toVector)
    }.toSeq
  }
}
