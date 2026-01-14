package mill.api.internal

private[mill] case class HeaderData(
    `extends`: Located[OneOrMore[Located[String]]] = Located(null, -1, OneOrMore(Nil)),
    moduleDeps: Located[Appendable[Seq[Located[String]]]] =
      Located(null, -1, Appendable(Nil)),
    compileModuleDeps: Located[Appendable[Seq[Located[String]]]] =
      Located(null, -1, Appendable(Nil)),
    runModuleDeps: Located[Appendable[Seq[Located[String]]]] =
      Located(null, -1, Appendable(Nil)),
    bomModuleDeps: Located[Appendable[Seq[Located[String]]]] =
      Located(null, -1, Appendable(Nil)),
    @upickle.implicits.flatten rest: Map[Located[String], upickle.core.BufferedValue]
)
private[mill] object HeaderData {

  import upickle.core.BufferedValue

  private implicit val bufferedR: upickle.Reader[BufferedValue] =
    new upickle.Reader.Delegate(BufferedValue.Builder)

  def headerDataReader(path: os.Path) = {
    implicit def locatedReader[T: upickle.Reader]: Located.UpickleReader[T] =
      new Located.UpickleReader[T](path)
    implicit def appendableReader[T: upickle.Reader]: Appendable.UpickleReader[T] =
      new Appendable.UpickleReader[T]
    upickle.macroR[HeaderData]
  }
}
