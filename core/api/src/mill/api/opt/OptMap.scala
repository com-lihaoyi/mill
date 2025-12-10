package mill.api.opt

import mill.api.daemon.internal.OptApi

type OptMap = Map[String, Opt]

extension (map: Map[String, OptApi]) {
  def toStringMap: Map[String, String] = map.view.mapValues(_.toString()).toMap
}

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

//  given jsonReadWriter: upickle.ReadWriter[OptMap] =
//    upickle.readwriter[Map[String, Opt]].bimap(_.map, OptMap(_))

}
