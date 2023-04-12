package hello

// Taken from https://github.com/lihaoyi/Metascala/blob/76dfbfa18484b9ee39bd09453328ea1081fcab6b/src/test/java/metascala/features/classes/Inheritance.java


class Cow {
  def moo: String = {
    return "moooo"
  }
}

class Bull extends Cow {
  def mooTwice: String = {
    return moo + moo
  }
}

class Matrix(aa: Float, ab: Float, ba: Float, bb: Float) {
  def determinant: Float = {
    return aa * bb - ab * ba
  }
}

class DoubleMatrix(aa: Float, ab: Float, ba: Float, bb: Float)
  extends Matrix(aa*2, ab*2, ba*2, bb*2)

class DoubleDetMatrix(aa: Float, ab: Float, ba: Float, bb: Float)
  extends Matrix(aa*2, ab*2, ba*2, bb*2){

  override def determinant: Float = {
    return super.determinant * 2
  }
}

class LinkedList {
  def push(i: Int) {
    val n = new Inner(i, head)
    head = n
  }

  def sum: Int = {
    var curr: Inner = head
    var total: Int = 0
    while (curr != null) {
      total = total + head.value
      curr = curr.next
    }
    return total
  }

  var head: Inner = null

  class Inner(val value: Int, val next: Inner)
}

/* EXPECTED TRANSITIVE
{
    "hello.Bull#<init>()V": [
        "hello.Cow#<init>()V"
    ],
    "hello.Bull#mooTwice()java.lang.String": [
        "hello.Cow#moo()java.lang.String"
    ],
    "hello.DoubleDetMatrix#<init>(F,F,F,F)V": [
        "hello.Matrix#<init>(F,F,F,F)V"
    ],
    "hello.DoubleDetMatrix#determinant()F": [
        "hello.Matrix#determinant()F"
    ],
    "hello.DoubleMatrix#<init>(F,F,F,F)V": [
        "hello.Matrix#<init>(F,F,F,F)V"
    ],
    "hello.LinkedList#push(I)V": [
        "hello.LinkedList#head()hello.LinkedList$Inner",
        "hello.LinkedList#head_$eq(hello.LinkedList$Inner)V",
        "hello.LinkedList$Inner#<init>(hello.LinkedList,I,hello.LinkedList$Inner)V"
    ],
    "hello.LinkedList#sum()I": [
        "hello.LinkedList#head()hello.LinkedList$Inner",
        "hello.LinkedList$Inner#next()hello.LinkedList$Inner",
        "hello.LinkedList$Inner#value()I"
    ]
}
*/
