package jawn
package ast

import scala.collection.mutable

object JawnFacade extends Facade[JValue] {

  final val jnull = JNull
  final val jfalse = JFalse
  final val jtrue = JTrue

  final def jnum(s: CharSequence, decIndex: Int, expIndex: Int): JValue =
    if (decIndex == -1 && expIndex == -1) {
      DeferLong(s.toString)
    } else {
      DeferNum(s.toString)
    }

  final def jstring(s: CharSequence): JValue =
    JString(s.toString)

  final def singleContext(): FContext[JValue] =
    new FContext[JValue] {
      var value: JValue = _
      def add(s: CharSequence) { value = JString(s.toString) }
      def add(v: JValue) { value = v }
      def finish: JValue = value
      def isObj: Boolean = false
    }

  final def arrayContext(): FContext[JValue] =
    new FContext[JValue] {
      val vs = mutable.ArrayBuffer.empty[JValue]
      def add(s: CharSequence) { vs.append(JString(s.toString)) }
      def add(v: JValue) { vs.append(v) }
      def finish: JValue = JArray(vs.toArray)
      def isObj: Boolean = false
    }

  final def objectContext(): FContext[JValue] =
    new FContext[JValue] {
      var key: String = null
      val vs = mutable.Map.empty[String, JValue]
      def add(s: CharSequence): Unit =
        if (key == null) { key = s.toString } else { vs(key.toString) = JString(s.toString); key = null }
      def add(v: JValue): Unit =
        { vs(key) = v; key = null }
      def finish = JObject(vs)
      def isObj = true
    }
}
