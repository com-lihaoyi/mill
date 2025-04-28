package mill.kotlinlib.js

import upickle.default.ReadWriter

enum SourceMapEmbedSourcesKind derives ReadWriter {
  case Always
  case Never
  case Inlining
}
