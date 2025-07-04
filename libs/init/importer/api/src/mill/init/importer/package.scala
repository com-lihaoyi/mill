package mill.init

import upickle.default.{ReadWriter, readwriter}

package object importer {
  implicit val rwSubPath: ReadWriter[os.SubPath] =
    readwriter[String].bimap(_.toString(), os.SubPath(_))
}
