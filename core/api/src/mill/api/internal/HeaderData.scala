package mill.api.internal

private[mill] case class HeaderData(
    `extends`: Located[OneOrMore[Located[String]]] = Located(null, -1, OneOrMore(Nil)),
    moduleDeps: AppendLocated[Seq[Located[String]]] =
      AppendLocated(Located(null, -1, Nil), append = false),
    compileModuleDeps: AppendLocated[Seq[Located[String]]] =
      AppendLocated(Located(null, -1, Nil), append = false),
    runModuleDeps: AppendLocated[Seq[Located[String]]] =
      AppendLocated(Located(null, -1, Nil), append = false),
    @upickle.implicits.flatten rest: Map[Located[String], upickle.core.BufferedValue]
)
private[mill] object HeaderData {

  import upickle.core.BufferedValue

  private implicit val bufferedR: upickle.Reader[BufferedValue] =
    new upickle.Reader.Delegate(BufferedValue.Builder)

  def headerDataReader(path: os.Path) = {
    implicit def locatedReader[T: upickle.Reader]: Located.UpickleReader[T] =
      new Located.UpickleReader[T](path)
    implicit def appendLocatedReader[T: upickle.Reader]: AppendLocated.UpickleReader[T] =
      new AppendLocated.UpickleReader[T](path)
    upickle.macroR[HeaderData]
  }
}
