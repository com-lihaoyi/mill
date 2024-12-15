package mill.codesig

class Logger(mandatoryLogFolder: os.Path, logFolder: Option[os.Path]) {
  logFolder.foreach(os.remove.all(_))
  os.remove.all(mandatoryLogFolder)
  private var count = 1

  def log0[T: upickle.default.Writer](p: os.Path, t: => sourcecode.Text[T], prefix: String = "") = {
    lazy val res = t
    os.write(
      p / s"$count-$prefix${res.source}.json",
      upickle.default.stream(res.value, indent = 4),
      createFolders = true
    )
    count += 1
  }
  def log[T: upickle.default.Writer](t: => sourcecode.Text[T], prefix: String = ""): Unit = {
    logFolder.foreach(log0(_, t, prefix))
  }
  def mandatoryLog[T: upickle.default.Writer](t: => sourcecode.Text[T], prefix: String = ""): Unit = {
    log0(mandatoryLogFolder, t, prefix)
  }
}
