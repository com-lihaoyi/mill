package mill.kotlinlib.js

import upickle.ReadWriter

enum SourceMapNamesPolicy derives ReadWriter {
  case SimpleNames
  case FullyQualifiedNames
  case No
}
