import sys
import foo

qux_val: int = foo.foo_val

def main() -> None: print("Hello, " + " ".join(sys.argv[1:]) + " Qux!")
if __name__ == "__main__":
    main()
