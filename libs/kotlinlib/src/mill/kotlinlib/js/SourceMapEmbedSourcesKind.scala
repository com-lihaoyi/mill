package mill.kotlinlib.js

import upickle.ReadWriter

enum SourceMapEmbedSourcesKind derives ReadWriter {
  case Always
  case Never
  case Inlining
}
