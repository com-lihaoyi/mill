package mill.kotlinlib.js

import upickle.default.ReadWriter

enum RunTarget derives ReadWriter {
  // TODO rely on the node version installed in the env or fetch a specific one?
  case Node
}
