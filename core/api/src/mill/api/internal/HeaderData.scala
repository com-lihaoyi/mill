package mill.api.internal

private[mill] case class HeaderData(
                                     `extends`: Located[Seq[Located[String]]] = Located(null, -1, Nil),
                                     moduleDeps: Located[Seq[Located[String]]] = Located(null, -1, Nil),
                                     compileModuleDeps: Located[Seq[Located[String]]] = Located(null, -1, Nil),
                                     runModuleDeps: Located[Seq[Located[String]]] = Located(null, -1, Nil),
                                     @upickle.implicits.flatten rest: Map[String, upickle.core.BufferedValue]
                                   )
private[mill] object HeaderData {

  import upickle.core.BufferedValue

  private implicit val bufferedR: upickle.Reader[BufferedValue] =
    new upickle.Reader.Delegate(BufferedValue.Builder)

  def headerDataReader(path: os.Path) = {
    implicit def locatedReader[T: upickle.Reader]: Located.UpickleReader[T] =
      new Located.UpickleReader[T](path)
    upickle.macroR[HeaderData]
  }
}
