//| resources: ["./resources"]

package foo

@main def main() = {
  println(os.read(os.resource / "file.txt"))
}
