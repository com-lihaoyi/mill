package forge.discover

import forge.define.Target
import play.api.libs.json.Format

case class Labelled[T](target: Target[T],
                       format: Format[T],
                       segments: Seq[String])
