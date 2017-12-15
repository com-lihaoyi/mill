package jawn
package ast

import org.scalatest._
import org.scalatest.prop._

import scala.collection.mutable
import scala.util.{Try, Success}

import ArbitraryUtil._

class AstTest extends PropSpec with Matchers with PropertyChecks {

  property("calling .get never crashes") {
    forAll { (v: JValue, s: String, i: Int) =>
      Try(v.get(i).get(s)).isSuccess shouldBe true
      Try(v.get(s).get(i)).isSuccess shouldBe true
      Try(v.get(i).get(i)).isSuccess shouldBe true
      Try(v.get(s).get(s)).isSuccess shouldBe true
    }
  }

  property(".getX and .asX agree") {
    forAll { (v: JValue) =>
      v.getBoolean shouldBe Try(v.asBoolean).toOption
      v.getString shouldBe Try(v.asString).toOption
      v.getInt shouldBe Try(v.asInt).toOption
      v.getLong shouldBe Try(v.asLong).toOption
      v.getDouble shouldBe Try(v.asDouble).toOption
      v.getBigInt shouldBe Try(v.asBigInt).toOption
      v.getBigDecimal shouldBe Try(v.asBigDecimal).toOption
    }
  }

  property(".getBoolean") {
    forAll((b: Boolean) => JBool(b).getBoolean shouldBe Some(b))
  }

  property(".getString") {
    forAll((s: String) => JString(s).getString shouldBe Some(s))
  }

  property(".getInt") {
    forAll { (n: Int) =>
      JNum(n).getInt shouldBe Some(n)
      JParser.parseUnsafe(n.toString).getInt shouldBe Some(n)
    }
  }

  property(".getLong") {
    forAll { (n: Long) =>
      JNum(n).getLong shouldBe Some(n)
      JParser.parseUnsafe(n.toString).getLong shouldBe Some(n)
    }
  }

  property(".getDouble") {
    forAll { (n: Double) =>
      JNum(n).getDouble shouldBe Some(n)
      JParser.parseUnsafe(n.toString).getDouble shouldBe Some(n)
    }
  }

  property(".getBigInt") {
    forAll { (n: BigInt) =>
      JNum(n.toString).getBigInt shouldBe Some(n)
      JParser.parseUnsafe(n.toString).getBigInt shouldBe Some(n)
    }
  }

  property(".getBigDecimal") {
    forAll { (n: BigDecimal) =>
      if (Try(BigDecimal(n.toString)) == Success(n)) {
        JNum(n.toString).getBigDecimal shouldBe Some(n)
        JParser.parseUnsafe(n.toString).getBigDecimal shouldBe Some(n)
      }
    }
  }
}
