package mill.runner.worker.api

trait MillScalaParser {
  def splitScript(rawCode: String, fileName: String): Either[String, (Seq[String], Seq[String])]
  def parseImportHooksWithIndices(stmts: Seq[String]): Seq[(String, Seq[ImportTree])]

  /* not sure if this is the right way, in case needs change, or if we should accept some
   * "generic" visitor over some "generic" trees?
   */
  def parseObjectData(rawCode: String): Seq[ObjectData]
}
