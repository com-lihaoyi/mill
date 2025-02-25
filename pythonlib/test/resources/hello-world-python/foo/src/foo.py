import sys
import bar

foo_val: int = bar.bar_val

def main() -> None: print("Hello, " + " ".join(sys.argv[1:]) + " Foo!")
if __name__ == "__main__":
    main()
