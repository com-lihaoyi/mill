import $file.^.foreignB.build
import $file.^.build

def taskA = T {
  ^.foreignB.build.taskB()
  println("a")
}

def taskD = T {
  ^.build.taskC()
  println("d")
}
