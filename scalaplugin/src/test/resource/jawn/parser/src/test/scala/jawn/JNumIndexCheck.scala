package jawn
package parser

import java.nio.ByteBuffer
import org.scalatest.{Matchers, PropSpec}
import org.scalatest.prop.PropertyChecks
import scala.util.Success

class JNumIndexCheck extends PropSpec with Matchers with PropertyChecks {
  object JNumIndexCheckFacade extends Facade[Boolean] {
    class JNumIndexCheckContext(val isObj: Boolean) extends FContext[Boolean] {
      var failed = false
      def add(s: CharSequence): Unit = ()
      def add(v: Boolean): Unit = {
        if (!v) failed = true
      }
      def finish: Boolean = !failed
    }

    val singleContext: FContext[Boolean] = new JNumIndexCheckContext(false)
    val arrayContext: FContext[Boolean] = new JNumIndexCheckContext(false)
    val objectContext: FContext[Boolean] = new JNumIndexCheckContext(true)

    def jnull(): Boolean = true
    def jfalse(): Boolean = true
    def jtrue(): Boolean = true
    def jnum(s: CharSequence, decIndex: Int, expIndex: Int): Boolean = {
      val input = s.toString
      val inputDecIndex = input.indexOf('.')
      val inputExpIndex = if (input.indexOf('e') == -1) input.indexOf("E") else input.indexOf('e')

      decIndex == inputDecIndex && expIndex == inputExpIndex
    }
    def jstring(s: CharSequence): Boolean = true
  }

  property("jnum provides the correct indices with parseFromString") {
    forAll { (value: BigDecimal) =>
      val json = s"""{ "num": ${value.toString} }"""
      Parser.parseFromString(json)(JNumIndexCheckFacade) shouldBe Success(true)
    }
  }

  property("jnum provides the correct indices with parseFromByteBuffer") {
    forAll { (value: BigDecimal) =>
      val json = s"""{ "num": ${value.toString} }"""
      val bb = ByteBuffer.wrap(json.getBytes("UTF-8"))
      Parser.parseFromByteBuffer(bb)(JNumIndexCheckFacade) shouldBe Success(true)
    }
  }

  property("jnum provides the correct indices with parseFromFile") {
    forAll { (value: BigDecimal) =>
      val json = s"""{ "num": ${value.toString} }"""
      TestUtil.withTemp(json) { t =>
        Parser.parseFromFile(t)(JNumIndexCheckFacade) shouldBe Success(true)
      }
    }
  }

  property("jnum provides the correct indices at the top level with parseFromString") {
    forAll { (value: BigDecimal) =>
      Parser.parseFromString(value.toString)(JNumIndexCheckFacade) shouldBe Success(true)
    }
  }

  property("jnum provides the correct indices at the top level with parseFromByteBuffer") {
    forAll { (value: BigDecimal) =>
      val bb = ByteBuffer.wrap(value.toString.getBytes("UTF-8"))
      Parser.parseFromByteBuffer(bb)(JNumIndexCheckFacade) shouldBe Success(true)
    }
  }

  property("jnum provides the correct indices at the top level with parseFromFile") {
    forAll { (value: BigDecimal) =>
      TestUtil.withTemp(value.toString) { t =>
        Parser.parseFromFile(t)(JNumIndexCheckFacade) shouldBe Success(true)
      }
    }
  }
}
