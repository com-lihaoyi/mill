package mill.rpc

import mill.rpc.MillRpcRequestId.{Kind, Part}
import utest.*

object MillRpcRequestIdTests extends TestSuite {

  val tests = Tests {
    test("Kind.unapply") {
      Kind.values.foreach { kind =>
        val actual = Kind.unapply(kind.asChar)
        assert(actual.contains(kind))
      }
    }

    test("Part.unapply") {
      test("c0") {
        val actual = Part.unapply("c0")
        assert(actual.contains(Part(Kind.Client, 0)))
      }

      test("c1") {
        val actual = Part.unapply("c1")
        assert(actual.contains(Part(Kind.Client, 1)))
      }

      test("s0") {
        val actual = Part.unapply("s0")
        assert(actual.contains(Part(Kind.Server, 0)))
      }

      test("s1") {
        val actual = Part.unapply("s1")
        assert(actual.contains(Part(Kind.Server, 1)))
      }
    }

    test("MillRpcRequestId") {
      test("fromString") {
        test("c0") {
          val actual = MillRpcRequestId.fromString("c0")
          assert(actual.contains(MillRpcRequestId.unsafe(Vector(Part(Kind.Client, 0)))))
        }

        test("c0:s0") {
          val actual = MillRpcRequestId.fromString("c0:s0")
          assert(actual.contains(MillRpcRequestId.unsafe(Vector(Part(Kind.Client, 0), Part(Kind.Server, 0)))))
        }
      }

      test("sequencing") {
        extension (id: MillRpcRequestId) def is(s: String): MillRpcRequestId = {
          assert(id.toString == s)
          id
        }

        MillRpcRequestId.initialForClient.is("c-1")
          .requestStartedFromClient.is("c0")
          .requestFinished.is("c0")
          .requestStartedFromClient.is("c1")
          .requestFinished.is("c1")
          .requestStartedFromServer.is("c1:s0")
          .requestStartedFromServer.is("c1:s1")
          .requestStartedFromClient.is("c1:s1:c0")
          .requestStartedFromClient.is("c1:s1:c1")
          .requestFinished.is("c1:s1")
          .requestFinished.is("c1")
      }
    }
  }
}
