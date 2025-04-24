package mill.kotlinlib.js

import upickle.default.ReadWriter

enum SourceMapNamesPolicy derives ReadWriter {
  case SimpleNames
  case FullyQualifiedNames
  case No
}
