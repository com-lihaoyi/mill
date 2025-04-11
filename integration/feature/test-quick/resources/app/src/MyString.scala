package app

import lib.*

final case class MyString(val value: String)

object MyString {

  given gCombinator: Combinator[MyString] = new Combinator[MyString] {
    def combine(a: MyString, b: MyString): MyString = MyString(a.value + b.value)
  }

  given gDefaultValue: DefaultValue[MyString] = new DefaultValue[MyString] {
    def defaultValue: MyString = MyString("")
  }

  def combine(a: MyString, b: MyString, c: MyString): MyString = gCombinator.combine2(a, b, c)

  def defaultValue: MyString = gDefaultValue.defaultValue

}
