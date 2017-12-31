package better.files

import org.scalatest.matchers.{MatchResult, Matcher}

import scala.reflect.ClassTag
import scala.util.control.ControlThrowable

class ManagedResourceSpec extends CommonSpec {
  // Test classes

  private class TestDisposable extends AutoCloseable {
    var closeCount = 0

    override def close(): Unit =
      closeCount += 1
  }

  private class TestDisposableThatThrows extends TestDisposable {
    override def close(): Unit = {
      super.close()
      throw new TestDisposeException
    }
  }

  private class TestDisposableThatThrowsFatal extends TestDisposable {
    override def close(): Unit = {
      super.close()
      throw new TestDisposeFatalException
    }
  }

  private class TestEvalException extends Exception
  private class TestDisposeException extends Exception
  private class TestDisposeFatalException extends Exception with ControlThrowable

  // Custom matchers

  private class HaveSuppressedMatcher(classes: Class[_ <: Throwable]*) extends Matcher[Throwable] {
    override def apply(left: Throwable): MatchResult = {
      MatchResult(
        (classes corresponds left.getSuppressed) {
          (clazz, suppressed) => clazz isInstance suppressed
        },
        s"had suppressed exceptions of types ${classes.map(_.getSimpleName).mkString(", ")}",
        s"had not suppressed exceptions of types ${classes.map(_.getSimpleName).mkString(", ")}"
      )
    }
  }

  private def haveSuppressed[E <: Throwable](implicit ct: ClassTag[E]) =
    new HaveSuppressedMatcher(ct.runtimeClass.asInstanceOf[Class[_ <: Throwable]])

  // Test body

  behavior of "managed resources"

  it should "map correctly" in {
    val t = new TestDisposable

    val result = for {
      tc <- t.autoClosed
    } yield {
      t.closeCount shouldBe 0
      "hello"
    }

    result shouldBe "hello"
    t.closeCount shouldBe 1
  }

  it should "flatMap correctly" in {
    val t = new TestDisposable

    val result = (for {
      tc <- t.autoClosed
      v <- Iterator("one", "two", "three")
    } yield {
      t.closeCount shouldBe 0
      v
    }).toSeq

    result should contain inOrder ("one", "two", "three")
    t.closeCount shouldBe 1
  }

  it should "handle exceptions correctly" in {
    val t = new TestDisposable

    a [TestEvalException] should be thrownBy {
      for {
        tc <- t.autoClosed
      } {
        t.closeCount shouldBe 0
        throw new TestEvalException
      }
    }
    t.closeCount shouldBe 1

    var lastSeen = ""
    a [TestEvalException] should be thrownBy {
      for {
        tc <- t.autoClosed
        v <- Iterator("one", "two", "three")
      } {
        t.closeCount shouldBe 1
        lastSeen = v
        if (v == "two") throw new TestEvalException
      }
    }
    t.closeCount shouldBe 2
    lastSeen shouldBe "two"
  }

  it should "handle disposal exceptions correctly" in {
    // For some mysterious reason, thrownBy doesn't work here, in this specific test case. No clue why, despite spending an entire day trying to figure it out,
    // including repeatedly stepping through the innards of ScalaTest in a debugger. Catching the exception manually does work, though.
    val messageNoException = "no exception was thrown"
    def messageWrongException(e: Throwable): String =
      s"an exception was thrown, but not a TestDisposeException; instead it's a ${e.getClass.getName}"

    val t = new TestDisposableThatThrows

    val e1 =
      try {
        for {
          tc <- t.autoClosed
        } {
          t.closeCount shouldBe 0
        }
        None
      }
      catch {
        case e: TestDisposeException =>
          Some(e)
      }
    assert(e1.nonEmpty, messageNoException)
    e1 foreach { e1c => assert(e1c.isInstanceOf[TestDisposeException], messageWrongException(e1c)) }
    t.closeCount shouldBe 1

    var lastSeen = ""
    val e2 =
      try {
        val i = for {
          tc <- t.autoClosed
          v <- Iterator("one", "two", "three")
        } yield {
          t.closeCount shouldBe 1
          lastSeen = v
          v
        }
        while (i.hasNext) i.next()
        None
      }
      catch {
        case e: TestDisposeException =>
          Some(e)
      }
    lastSeen shouldBe "three"
    assert(e2.nonEmpty, messageNoException)
    e2 foreach { e2c => assert(e2c.isInstanceOf[TestDisposeException], messageWrongException(e2c)) }
    t.closeCount shouldBe 2
  }

  it should "handle non-local returns correctly" in {
    val t = new TestDisposable

    def doTheThing(): String = {
      throw the [ControlThrowable] thrownBy {
        for {
          tc <- t.autoClosed
        } {
          t.closeCount shouldBe 0
          return "hello"
        }
      }
    }
    doTheThing() shouldBe "hello"
    t.closeCount shouldBe 1

    def doTheThings(): String = {
      throw the [ControlThrowable] thrownBy {
        for {
          tc <- t.autoClosed
          v <- Iterator("one", "two", "three")
        } {
          t.closeCount shouldBe 1
          if (v == "two") return v
        }
      }
    }
    doTheThings() shouldBe "two"
    t.closeCount shouldBe 2
  }

  it should "handle multiple exceptions correctly" in {
    val t = new TestDisposableThatThrows

    the [TestEvalException] thrownBy {
      for {
        tc <- t.autoClosed
      } {
        t.closeCount shouldBe 0
        throw new TestEvalException
      }
    } should haveSuppressed [TestDisposeException]
    t.closeCount shouldBe 1

    var lastSeen = ""
    the [TestEvalException] thrownBy {
      for {
        tc <- t.autoClosed
        v <- Iterator("one", "two", "three")
      } {
        t.closeCount shouldBe 1
        lastSeen = v
        if (v == "two") throw new TestEvalException
      }
    } should haveSuppressed [TestDisposeException]
    lastSeen shouldBe "two"
    t.closeCount shouldBe 2
  }

  it should "give fatal exceptions precedence" in {
    val t = new TestDisposableThatThrowsFatal

    the [TestDisposeFatalException] thrownBy {
      for {
        tc <- t.autoClosed
      } {
        t.closeCount shouldBe 0
        throw new TestEvalException
      }
    } should haveSuppressed [TestEvalException]
    t.closeCount shouldBe 1

    var lastSeen = ""
    the [TestDisposeFatalException] thrownBy {
      for {
        tc <- t.autoClosed
        v <- Iterator("one", "two", "three")
      } {
        t.closeCount shouldBe 1
        lastSeen = v
        if (v == "two") throw new TestEvalException
      }
    } should haveSuppressed [TestEvalException]
    t.closeCount shouldBe 2
    lastSeen shouldBe "two"
  }
}
