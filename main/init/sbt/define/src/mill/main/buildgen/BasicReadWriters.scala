package mill.main.buildgen

import upickle.default.{readwriter, ReadWriter => RW}

object BasicReadWriters {
  implicit val relPath: RW[os.RelPath] = readwriter[String].bimap(_.toString(), os.RelPath(_))
  implicit val subPath: RW[os.SubPath] = readwriter[String].bimap(_.toString(), os.SubPath(_))
}
