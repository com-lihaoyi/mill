package mill.discover

import mill.{Module, T}
import mill.define.Cross
import utest._

object CrossModuleTests extends TestSuite{

  val tests = Tests{

    'cross - {
      object outer{
        val crossed =
          for(n <- Cross("2.10.6", "2.11.8", "2.12.4"))
          yield new Module{
            def scalaVersion = n
          }
      }

      val Some((gen, innerMirror)) = Discovered[outer.type]
        .mirror
        .children
        .head._2
        .asInstanceOf[Mirror[outer.type, outer.crossed.type]]
        .crossChildren

      val keys = gen(outer.crossed)
      assert(keys == List(List("2.10.6"), List("2.11.8"), List("2.12.4")))
      for(k <- keys){
        assert(outer.crossed(k).scalaVersion == k.head)
      }
    }
    'doubleCross - {
      object outer{
        val crossed =
          for{
            platform <- Cross("", "sjs0.6", "native0.3")
            scalaVersion <- Cross("2.10.6", "2.11.8", "2.12.4")
            if !(platform == "native0.3" && scalaVersion == "2.10.6")
          } yield new Module{
            def suffix = Seq(scalaVersion, platform).filter(_.nonEmpty).map("_"+_).mkString
          }
      }

      val Some((gen, innerMirror)) = Discovered[outer.type]
        .mirror
        .children
        .head._2
        .asInstanceOf[Mirror[outer.type, outer.crossed.type]]
        .crossChildren

      val keys = gen(outer.crossed)
      val expectedKeys = List(
        List("2.10.6", ""),
        List("2.11.8", ""),
        List("2.12.4", ""),
        List("2.10.6", "sjs0.6"),
        List("2.11.8", "sjs0.6"),
        List("2.12.4", "sjs0.6"),
        List("2.11.8", "native0.3"),
        List("2.12.4", "native0.3"),
      )

      assert(keys == expectedKeys)
      for(k <- keys){
        val suffix = outer.crossed(k).suffix
        val expected = k.map(_.toString).filter(_.nonEmpty).map("_"+_).mkString
        assert(suffix == expected)
      }
    }
    'crossCommands - {
      object outer {
        val cross = for (c <- mill.define.Cross("a", "b", "c")) yield new mill.Module {
          def printIt() = T.command {
            println("PRINTING IT: " + c)
          }
        }
      }
    }
  }
}

