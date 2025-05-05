package mill.compilerworker.api
trait ObjectData {
  def obj: Snip
  def name: Snip
  def parent: Snip
  def endMarker: Option[Snip]
  def finalStat: Option[(String, Snip)]
}
