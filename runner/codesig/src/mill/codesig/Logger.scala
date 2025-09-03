package mill.codesig

class Logger(mandatoryLogFolder: os.Path, logFolder: Option[os.Path]) {
  logFolder.foreach(os.remove.all(_))
  os.remove.all(mandatoryLogFolder)
  private var count = 1

  def log0[T: upickle.Writer](
      p: os.Path,
      res: sourcecode.Text[T],
      prefix: String = ""
  ): Unit = {
    os.write(
      p / s"$prefix${res.source}.json",
      upickle.stream(res.value, indent = 2),
      createFolders = true
    )
    count += 1
  }
  def log[T: upickle.Writer](t: => sourcecode.Text[T], prefix: String = ""): Unit = {
    logFolder.foreach(log0(_, t, s"$count-$prefix"))
  }
  def mandatoryLog[T: upickle.Writer](
      t: => sourcecode.Text[T],
      prefix: String = ""
  ): Unit = {
    log0(mandatoryLogFolder, t, prefix)
  }
}
