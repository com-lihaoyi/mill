package fail.halfpackagecycle
package c

class C2 {
  lazy val b = new B
}
