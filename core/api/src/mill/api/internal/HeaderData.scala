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
    `mill-precompiled-module`: Located[Boolean] = Located(null, -1, false),
    @upickle.implicits.flatten rest: Map[Located[String], upickle.core.BufferedValue]
)
private[mill] object HeaderData {

  import upickle.core.BufferedValue

  private def nestedHeaderDataError(
      scriptPath: os.Path,
      abort: upickle.core.AbortException
  ) = {
    val message = Option(abort.getMessage).getOrElse("YAML type mismatch")
    throw new mill.api.daemon.Result.Exception(
      message,
      Some(mill.api.daemon.Result.Failure(message, scriptPath.toNIO, abort.index))
    )
  }

  private implicit val bufferedR: upickle.Reader[BufferedValue] =
    new upickle.Reader.Delegate(BufferedValue.Builder)

  def headerDataReader(path: os.Path) = {
    implicit def locatedReader[T: upickle.Reader]: Located.UpickleReader[T] =
      new Located.UpickleReader[T](path)
    implicit def appendableReader[T: upickle.Reader]: Appendable.UpickleReader[T] =
      new Appendable.UpickleReader[T]
    upickle.macroR[HeaderData]
  }

  /**
   * Iterates over the `rest` entries in a [[HeaderData]], dispatching to `onProperty`
   * for simple keys and `onNestedObject` for `object <name>:` keys. Used by both
   * code generation and precompiled module dynamic override flattening.
   */
  def processRest[T](
      scriptPath: os.Path,
      data: HeaderData
  )(
      onProperty: (Located[String], BufferedValue) => T,
      onNestedObject: (Located[String], String, HeaderData) => T
  ): Seq[T] = {
    for ((locatedKey, v) <- data.rest.toSeq)
      yield locatedKey.value.split(" +") match {
        case Array(_) => onProperty(locatedKey, v)
        case Array("object", name) =>
          val nestedData =
            try BufferedValue.transform(v, headerDataReader(scriptPath))
            catch {
              case abort: upickle.core.AbortException =>
                nestedHeaderDataError(scriptPath, abort)
            }
          onNestedObject(locatedKey, name, nestedData)
        case _ => throw new mill.api.daemon.Result.Exception(
            "",
            Some(mill.api.daemon.Result.Failure(
              "Invalid key: " + locatedKey.value,
              scriptPath.toNIO,
              locatedKey.index
            ))
          )
      }
  }
}
