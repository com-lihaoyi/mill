package jawn
package support.json4s

import scala.collection.mutable
import org.json4s.JsonAST._

object Parser extends Parser(false, false)

class Parser(useBigDecimalForDouble: Boolean, useBigIntForLong: Boolean) extends SupportParser[JValue] {

  implicit val facade: Facade[JValue] =
    new Facade[JValue] {
      def jnull() = JNull
      def jfalse() = JBool(false)
      def jtrue() = JBool(true)

      def jnum(s: CharSequence, decIndex: Int, expIndex: Int) =
        if (decIndex == -1 && expIndex == -1) {
          if (useBigIntForLong) JInt(BigInt(s.toString))
          else JLong(util.parseLongUnsafe(s))
        } else {
          if (useBigDecimalForDouble) JDecimal(BigDecimal(s.toString))
          else JDouble(s.toString.toDouble)
        }

      def jstring(s: CharSequence) = JString(s.toString)

      def singleContext() =
        new FContext[JValue] {
          var value: JValue = null
          def add(s: CharSequence) { value = jstring(s) }
          def add(v: JValue) { value = v }
          def finish: JValue = value
          def isObj: Boolean = false
        }

      def arrayContext() =
        new FContext[JValue] {
          val vs = mutable.ListBuffer.empty[JValue]
          def add(s: CharSequence) { vs += jstring(s) }
          def add(v: JValue) { vs += v }
          def finish: JValue = JArray(vs.toList)
          def isObj: Boolean = false
        }

      def objectContext() =
        new FContext[JValue] {
          var key: String = null
          val vs = mutable.ListBuffer.empty[JField]
          def add(s: CharSequence): Unit =
            if (key == null) key = s.toString
            else { vs += JField(key, jstring(s)); key = null }
          def add(v: JValue): Unit =
            { vs += JField(key, v); key = null }
          def finish: JValue = JObject(vs.toList)
          def isObj: Boolean = true
        }
    }
}
