package jawn
package support.play

import play.api.libs.json._

object Parser extends SupportParser[JsValue] {

  implicit val facade: Facade[JsValue] =
    new SimpleFacade[JsValue] {
      def jnull() = JsNull
      def jfalse() = JsBoolean(false)
      def jtrue() = JsBoolean(true)

      def jnum(s: CharSequence, decIndex: Int, expIndex: Int) = JsNumber(BigDecimal(s.toString))
      def jstring(s: CharSequence) = JsString(s.toString)

      def jarray(vs: List[JsValue]) = JsArray(vs)
      def jobject(vs: Map[String, JsValue]) = JsObject(vs)
    }
}
