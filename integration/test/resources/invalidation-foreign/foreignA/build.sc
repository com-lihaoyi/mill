import $file.^.foreignB.build

def taskA = T {
  ^.foreignB.build.taskB()
  println("a")
}
