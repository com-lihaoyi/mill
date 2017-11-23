package mill.discover

import java.io.InputStreamReader

import mill.discover.Router.{ArgSig, EntryPoint}
import utest._
import mill.{Module, T}
import mill.util.TestUtil.test
import mill.discover.Mirror.Segment.Label
object DiscoveredTests extends TestSuite{

  val tests = Tests{

    'targets - {
      class CanNest extends Module{
        val single = test()
        val invisible: Any = test()
        val invisible2: mill.define.Task[Int] = test()
        val invisible3: mill.define.Task[_] = test()
      }
      object outer {
        val single = test()
        val invisible: Any = test()
        object nested extends Module{
          val single = test()
          val invisible: Any = test()

        }
        val classInstance = new CanNest

      }

      val discovered = Discovered[outer.type]


      def flatten(h: Mirror[outer.type, _]): Seq[Any] = {
        h.node(outer, Nil) :: h.children.flatMap{case (label, c) => flatten(c)}
      }
      val flattenedHierarchy = flatten(discovered.mirror)

      val expectedHierarchy = Seq(
        outer,
        outer.classInstance,
        outer.nested,
      )
      assert(flattenedHierarchy == expectedHierarchy)

      val mapped = discovered.targets(outer).map(x => x.segments -> x.target)

      val expected = Seq(
        (List(Label("classInstance"), Label("single")), outer.classInstance.single),
        (List(Label("nested"), Label("single")), outer.nested.single),
        (List(Label("single")), outer.single)
      )
      assert(mapped.toSet == expected.toSet)
    }

    'commands - {
      object outer {
        def hello() = T.command{
          println("Hello")
        }
        def echoPair(prefix: String, suffix: String) = T.command{
          println(prefix + " " + suffix)
        }
        object nested extends Module{
          def inner(x: Int) = T.command{
            println(x)
          }
        }

      }

      val discovered = Discovered[outer.type]
      val outerCommands = discovered.mirror.commands

      assertMatch(outerCommands){case Seq(
        EntryPoint("echoPair",
          List(ArgSig("prefix", "String", None, None), ArgSig("suffix", "String", None, None)),
          None,
          false,
          _
        ),
        EntryPoint("hello", Nil, None, false, _)
      ) =>}

      val innerCommands = discovered.mirror
        .children
        .flatMap(_._2.commands.asInstanceOf[Seq[EntryPoint[_]]])

      assertMatch(innerCommands){case Seq(
        EntryPoint("inner", _, None, false, _),
      ) =>}
    }

    'compileError - {
      'unserializableTarget - {

        object outer extends Module {
          def single = mill.T{ new InputStreamReader(System.in) }
        }

        val error = compileError("Discovered[outer.type]")
        assert(
          error.msg.contains("could not find implicit value"),
          error.pos.contains("def single = mill.T{ new InputStreamReader(System.in) }")
        )
      }

      'unreadableCommand - {
        object outer extends Module {
          def single(in: InputStreamReader) = mill.T.command{ println(123) }
        }

        val error = compileError("Discovered[outer.type]")

        assert(
          error.msg.contains("could not find implicit value"),
          error.pos.contains("def single(in: InputStreamReader) = mill.T.command{ println(123) }")
        )
      }
    }
  }
}
