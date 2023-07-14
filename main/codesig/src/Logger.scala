package mill.codesig

class Logger(logFolder: Option[os.Path]) {
  logFolder.foreach(os.remove.all(_))
  private var count = 1

  def log[T: upickle.default.Writer](t: => sourcecode.Text[T], prefix: String = ""): Unit = {
    lazy val res = t
    logFolder.foreach { p =>
      os.write(
        p / s"$count-$prefix${res.source}.json",
        upickle.default.stream(res.value, indent = 4),
        createFolders = true
      )
      count += 1
    }
  }
}
