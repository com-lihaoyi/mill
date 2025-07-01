package foo

import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonObject
import scala.jdk.CollectionConverters.*
object Foo {

  def main(args: Array[String]): Unit = {
    val jsonString = args(0)
    val jsonObj = JsonParser.`object`.from(jsonString)

    for (entry <- jsonObj.entrySet.asScala) {
      println("Key: " + entry.getKey + ", Value: " + entry.getValue)
    }
  }
}
