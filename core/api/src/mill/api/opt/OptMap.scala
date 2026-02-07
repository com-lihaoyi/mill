package mill.api.opt

import mill.api.daemon.experimental
import mill.api.daemon.internal.OptApi

@experimental
type OptMap = Map[String, Opt]

extension (map: Map[String, OptApi]) {
  def toStringMap: Map[String, String] = map.view.mapValues(_.toString()).toMap
}

@experimental
object OptMap {

  def apply(elems: (String, String | os.Path | Opt)*): OptMap = Map.from(
    elems
      .map { (k, v) =>
        (
          k,
          v match {
            case o: Opt => o
            case o: (String | os.Path) => Opt(o)
          }
        )
      }
  )

}
