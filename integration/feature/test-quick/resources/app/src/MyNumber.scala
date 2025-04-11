package app

import lib.*

final case class MyNumber(val value: Int)

object MyNumber {
  
  given gCombinator: Combinator[MyNumber] = new Combinator[MyNumber] {
    def combine(a: MyNumber, b: MyNumber): MyNumber = MyNumber(a.value + b.value)
  }

  given gDefaultValue: DefaultValue[MyNumber] = new DefaultValue[MyNumber] {
    def defaultValue: MyNumber = MyNumber(0)
  }

  def combine(a: MyNumber, b: MyNumber, c: MyNumber): MyNumber = gCombinator.combine2(a, b, c)

  def defaultValue: MyNumber = gDefaultValue.defaultValue

}
