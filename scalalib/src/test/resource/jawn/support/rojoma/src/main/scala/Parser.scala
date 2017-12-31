package jawn
package support.rojoma

import scala.collection.mutable
import com.rojoma.json.ast._

object Parser extends SupportParser[JValue] {
  implicit val facade: Facade[JValue] =
    new MutableFacade[JValue] {
      def jnull() = JNull
      def jfalse() = JBoolean.canonicalFalse
      def jtrue() = JBoolean.canonicalTrue
      def jnum(s: CharSequence, decIndex: Int, expIndex: Int) = JNumber(BigDecimal(s.toString))
      def jstring(s: CharSequence) = JString(s.toString)
      def jarray(vs: mutable.ArrayBuffer[JValue]) = JArray(vs)
      def jobject(vs: mutable.Map[String, JValue]) = JObject(vs)
    }
}
