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
    extends Matrix(aa * 2, ab * 2, ba * 2, bb * 2)

class DoubleDetMatrix(aa: Float, ab: Float, ba: Float, bb: Float)
    extends Matrix(aa * 2, ab * 2, ba * 2, bb * 2) {

  override def determinant: Float = {
    return super.determinant * 2
  }
}

class LinkedList {
  def push(i: Int): Unit = {
    val n = Inner(i, head)
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

/* expected-direct-call-graph
{
    "hello.Bull#<init>()void": [
        "hello.Cow#<init>()void"
    ],
    "hello.Bull#mooTwice()java.lang.String": [
        "hello.Cow#moo()java.lang.String"
    ],
    "hello.DoubleDetMatrix#<init>(float,float,float,float)void": [
        "hello.Matrix#<init>(float,float,float,float)void"
    ],
    "hello.DoubleDetMatrix#determinant()float": [
        "hello.Matrix#determinant()float"
    ],
    "hello.DoubleMatrix#<init>(float,float,float,float)void": [
        "hello.Matrix#<init>(float,float,float,float)void"
    ],
    "hello.LinkedList#push(int)void": [
        "hello.LinkedList#head()hello.LinkedList$Inner",
        "hello.LinkedList#head_$eq(hello.LinkedList$Inner)void",
        "hello.LinkedList$Inner#<init>(hello.LinkedList,int,hello.LinkedList$Inner)void"
    ],
    "hello.LinkedList#sum()int": [
        "hello.LinkedList#head()hello.LinkedList$Inner",
        "hello.LinkedList$Inner#next()hello.LinkedList$Inner",
        "hello.LinkedList$Inner#value()int"
    ]
}
 */
