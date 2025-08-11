package mill.scalajslib.api

import upickle.default.ReadWriter

enum ESModuleImportMapping derives ReadWriter {
  case Prefix(prefix: String, replacement: String)
}
