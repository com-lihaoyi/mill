//| resources: [resources/]

package foo

def main() = {
  println(os.read(os.resource / "file.txt"))
}
