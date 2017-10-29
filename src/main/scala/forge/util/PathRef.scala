package forge
package util

import ammonite.ops.ls
import play.api.libs.json.{Format, Json}


/**
  * A wrapper around `ammonite.ops.Path` that calculates it's hashcode based
  * on the contents of the filesystem underneath it. Used to ensure filesystem
  * changes can bust caches which are keyed off hashcodes.
  */
case class PathRef(path: ammonite.ops.Path){
  override def hashCode() = {
    if (!path.isDir) path.hashCode() + path.mtime.toMillis.toInt
    else ls.rec.iter(path)
          .filter(_.isFile)
          .map(x => x.toString.hashCode + x.mtime.toMillis)
          .sum
          .toInt
  }
}

object PathRef{
  implicit def jsonFormatter: Format[PathRef] = Json.format
}