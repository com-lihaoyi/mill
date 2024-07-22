import mill._


def inheritInterleaved = T {
  for (i <- Range.inclusive(1, 9)) {
    println("print stdout" + i)
    os.proc("echo", "proc stdout" + i).call(stdout = os.Inherit)
    System.err.println("print stderr" + i)
    os.proc("bash", "-c", s"echo proc stderr${i} >&2").call(stderr = os.Inherit)
  }
}

def inheritRaw = T{
  println("print stdoutRaw")
  os.proc("echo", "proc stdoutRaw").call(stdout = os.InheritRaw)
  System.err.println("print stderrRaw")
  os.proc("bash", "-c", "echo proc stderrRaw >&2").call(stderr = os.InheritRaw)
}