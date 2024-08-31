package build.e
import mill._

def input = T {
  println("e")
  build.a.input()
}
