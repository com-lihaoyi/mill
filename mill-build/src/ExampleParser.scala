package mill.testkit

object ExampleParser {
  def apply(testRepoRoot: os.Path): Seq[(String, String)] = {

    val states = collection.mutable.Buffer("scala")
    val chunks = collection.mutable.Buffer(collection.mutable.Buffer.empty[String])

    val rootBuildFileNames = Seq("build.sc", "build.mill", "build.mill.scala")
    val buildFile = rootBuildFileNames.map(testRepoRoot / _)
      .find(os.exists)
      .getOrElse(
        sys.error(
          s"No build file named ${rootBuildFileNames.mkString("/")} found in $testRepoRoot"
        )
      )
    for (line <- os.read.lines(buildFile)) {
      val (newState, restOpt) = line match {
        case s"/** Usage" => ("example", None)
        case s"/** See Also: $path */" =>
          (s"see:$path", Some(os.read(os.Path(path, testRepoRoot))))
        case s"*/" => ("scala", None)
        case s"//$rest" => ("comment", Some(rest.stripPrefix(" ")))
        case l => (if (states.last == "comment") "scala" else states.last, Some(l))
      }

      if (newState != states.last) {
        states.append(newState)
        chunks.append(collection.mutable.Buffer.empty[String])
      }

      restOpt.foreach(r => chunks.last.append(r))
    }

    states.zip(chunks.map(_.mkString("\n").trim)).filter(_._2.nonEmpty).toSeq
  }
}
