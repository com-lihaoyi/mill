package mill.discover

import java.io.InputStreamReader

import ammonite.main.Router.{ArgSig, EntryPoint}
import utest._
import mill.{Module, T}
import mill.define.Segment.Label
import mill.define.Segments
import mill.util.TestGraphs.{TraitWithModuleObject, nestedModule}
import mill.util.TestUtil
object DiscoveredTests extends TestSuite{

  val tests = Tests{

    'targets - {
      val discovered = Discovered.mapping(nestedModule)

      def flatten(h: Mirror[nestedModule.type, _]): Seq[Any] = {
        h.node(nestedModule, Nil) :: h.children.flatMap{case (label, c) => flatten(c)}
      }
      val flattenedHierarchy = flatten(discovered.mirror)

      val expectedHierarchy = Seq(
        nestedModule,
        nestedModule.classInstance,
        nestedModule.nested
      )
      assert(flattenedHierarchy == expectedHierarchy)

      val mapped = discovered.segmentsToTargets

      val expected = Map(
        (Segments(Label("classInstance"), Label("single")), nestedModule.classInstance.single),
        (Segments(Label("nested"), Label("single")), nestedModule.nested.single),
        (Segments(Label("single")), nestedModule.single)
      )
      assert(mapped == expected)
    }

    'traitWithModule - {
      val discovered = Discovered.mapping(TraitWithModuleObject)
      val mapped = discovered.segmentsToTargets
      val expected = Map(
        (
          Segments(Label("TraitModule"), Label("testFramework")),
          TraitWithModuleObject.TraitModule.testFramework
        )
      )
      assert(mapped == expected)
    }

    'commands - {
      object outer extends TestUtil.BaseModule{
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

      val discovered = Discovered.make[outer.type]
      val outerCommands = discovered.mirror.commands

      assertMatch(outerCommands){case Seq(
        EntryPoint("hello", Nil, None, false, _, _),
        EntryPoint("echoPair",
          List(ArgSig("prefix", "String", None, None), ArgSig("suffix", "String", None, None)),
          None,
          false,
          _,
          _
        )
      ) =>}

      val innerCommands = discovered.mirror
        .children
        .flatMap(_._2.commands.asInstanceOf[Seq[EntryPoint[_]]])

      assertMatch(innerCommands){case Seq(
        EntryPoint("inner", _, None, false, _, _),
      ) =>}
    }

    'compileError - {
      'unserializableTarget - {


        object outer extends TestUtil.BaseModule {
          val error = compileError("def single = mill.T{ new InputStreamReader(System.in) }")
        }


        assert(
          outer.error.msg.contains("uPickle does not know how to read"),
          outer.error.pos.contains("def single = mill.T{ new InputStreamReader(System.in) }")
        )
      }

      'unreadableCommand - {
        object outer extends TestUtil.BaseModule {
          def single(in: InputStreamReader) = mill.T.command{ println(123) }
        }

        val error = compileError("Discovered.make[outer.type]")

        assert(
          error.msg.contains("could not find implicit value"),
          error.pos.contains("def single(in: InputStreamReader) = mill.T.command{ println(123) }")
        )
      }
    }
  }
}
