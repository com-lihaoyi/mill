import sys

bar_val: int = 42

def main() -> None: print("Hello, " + " ".join(sys.argv[1:]) + " Foo Bar!")
if __name__ == "__main__":
    main()
