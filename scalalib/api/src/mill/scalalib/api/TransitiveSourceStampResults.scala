package mill.scalalib.api

final case class TransitiveSourceStampResults(
  currentStamps: Map[String, String],
  previousStamps: Option[Map[String, String]] = None
) {
  lazy val changedSources: Set[String] = {
    previousStamps match {
      case Some(prevStamps) =>
        currentStamps.view
        .flatMap { (source, stamp) =>
          prevStamps.get(source) match {
            case None => Some(source) // new source
            case Some(prevStamp) => Option.when(stamp != prevStamp)(source) // changed source
          }
        }
        .toSet
      case None => currentStamps.keySet
    }
  }
}

object TransitiveSourceStampResults {
  implicit val jsonFormatter: upickle.default.ReadWriter[TransitiveSourceStampResults] =
    upickle.default.macroRW
}