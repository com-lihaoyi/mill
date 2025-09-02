package mill.scalajslib.api

import upickle.ReadWriter

enum ESModuleImportMapping derives ReadWriter {
  case Prefix(prefix: String, replacement: String)
}
