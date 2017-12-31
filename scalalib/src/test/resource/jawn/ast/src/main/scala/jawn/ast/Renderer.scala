package jawn
package ast

import scala.annotation.switch
import scala.collection.mutable
import scala.util.Sorting

sealed trait Renderer {
  final def render(jv: JValue): String = {
    val sb = new StringBuilder
    render(sb, 0, jv)
    sb.toString
  }

  final def render(sb: StringBuilder, depth: Int, jv: JValue): Unit =
    jv match {
      case JNull => sb.append("null")
      case JTrue => sb.append("true")
      case JFalse => sb.append("false")
      case LongNum(n) => sb.append(n.toString)
      case DoubleNum(n) => sb.append(n.toString)
      case DeferNum(s) => sb.append(s)
      case DeferLong(s) => sb.append(s)
      case JString(s) => renderString(sb, s)
      case JArray(vs) => renderArray(sb, depth, vs)
      case JObject(vs) => renderObject(sb, depth, canonicalizeObject(vs))
    }

  def canonicalizeObject(vs: mutable.Map[String, JValue]): Iterator[(String, JValue)]

  def renderString(sb: StringBuilder, s: String): Unit

  final def renderArray(sb: StringBuilder, depth: Int, vs: Array[JValue]): Unit = {
    if (vs.isEmpty) return { sb.append("[]"); () }
    sb.append("[")
    render(sb, depth + 1, vs(0))
    var i = 1
    while (i < vs.length) {
      sb.append(",")
      render(sb, depth + 1, vs(i))
      i += 1
    }
    sb.append("]")
  }

  final def renderObject(sb: StringBuilder, depth: Int, it: Iterator[(String, JValue)]): Unit = {
    if (!it.hasNext) return { sb.append("{}"); () }
    val (k0, v0) = it.next
    sb.append("{")
    renderString(sb, k0)
    sb.append(":")
    render(sb, depth + 1, v0)
    while (it.hasNext) {
      val (k, v) = it.next
      sb.append(",")
      renderString(sb, k)
      sb.append(":")
      render(sb, depth + 1, v)
    }
    sb.append("}")
  }

  final def escape(sb: StringBuilder, s: String, unicode: Boolean): Unit = {
    sb.append('"')
    var i = 0
    val len = s.length
    while (i < len) {
      (s.charAt(i): @switch) match {
        case '"' => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c =>
          if (c < ' ' || (c > '~' && unicode)) sb.append("\\u%04x" format c.toInt)
          else sb.append(c)
      }
      i += 1
    }
    sb.append('"')
  }
}

object CanonicalRenderer extends Renderer {
  def canonicalizeObject(vs: mutable.Map[String, JValue]): Iterator[(String, JValue)] = {
    val keys = vs.keys.toArray
    Sorting.quickSort(keys)
    keys.iterator.map(k => (k, vs(k)))
  }
  def renderString(sb: StringBuilder, s: String): Unit =
    escape(sb, s, true)
}

object FastRenderer extends Renderer {
  def canonicalizeObject(vs: mutable.Map[String, JValue]): Iterator[(String, JValue)] =
    vs.iterator
  def renderString(sb: StringBuilder, s: String): Unit =
    escape(sb, s, false)
}
