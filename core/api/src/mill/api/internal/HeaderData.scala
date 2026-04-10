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
    `mill-experimental-precompiled-module`: Located[Boolean] = Located(null, -1, false),
    @upickle.implicits.flatten rest: Map[Located[String], upickle.core.BufferedValue]
)
private[mill] object HeaderData {

  import upickle.core.BufferedValue

  private def nestedHeaderDataError(
      scriptPath: os.Path,
      name: String,
      abort: upickle.core.AbortException
  ) = {
    val inner = Option(abort.getMessage).getOrElse("YAML type mismatch")
    val message = s"In object ${pprint.Util.literalize(name)}: $inner"
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
                nestedHeaderDataError(scriptPath, name, abort)
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

  /**
   * Entry for nested module deps collected from a HeaderData tree.
   * `key` is the nested path (e.g. "" for root, "test" for a nested test object).
   */
  case class NestedModuleDeps(
      key: String,
      moduleDeps: Seq[Located[String]],
      compileModuleDeps: Seq[Located[String]],
      runModuleDeps: Seq[Located[String]],
      bomModuleDeps: Seq[Located[String]]
  )

  /**
   * Recursively collects moduleDeps, compileModuleDeps, runModuleDeps, and bomModuleDeps
   * from a HeaderData tree, keyed by nested path (e.g. "" for root, "test" for nested test).
   * Also returns the set of nested object names found at each level, for validation.
   *
   * Used by both ScriptModuleInit (runtime resolution) and CodeGen (source generation).
   */
  def collectAllNestedDeps(
      scriptFile: os.Path,
      data: HeaderData,
      prefix: String
  ): Seq[NestedModuleDeps] = {
    val current = Seq(NestedModuleDeps(
      prefix,
      data.moduleDeps.value.value,
      data.compileModuleDeps.value.value,
      data.runModuleDeps.value.value,
      data.bomModuleDeps.value.value
    ))

    val nested = processRest(scriptFile, data)(
      onProperty = (_, _) => Seq.empty[NestedModuleDeps],
      onNestedObject = (_, name, nestedData) =>
        collectAllNestedDeps(
          scriptFile,
          nestedData,
          if (prefix.isEmpty) name else s"$prefix.$name"
        )
    ).flatten

    current ++ nested
  }
}
