package mill.codesig

class Logger(logFolder: Option[os.Path]) {
  logFolder.foreach(os.remove.all(_))
  private var count = 1
  def apply[T: upickle.default.Writer](t: => T, prefix: String = "")(implicit
      s: sourcecode.Name
  ): T = {
    apply0(t, prefix, s.value)
  }

  def log[T: upickle.default.Writer](t: => sourcecode.Text[T], prefix: String = ""): Unit = {
    val res = t
    apply0(res.value, prefix, res.source)
  }

  def apply0[T: upickle.default.Writer](t: => T, prefix: String, name: String): T = {
    val res = t
    logFolder.foreach { p =>
      os.write(
        p / s"$count-$prefix$name.json",
        upickle.default.stream(res, indent = 4),
        createFolders = true
      )
      count += 1
    }
    res
  }
}
