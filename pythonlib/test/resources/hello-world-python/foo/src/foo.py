import sys
def main() -> None: print("Hello, " + " ".join(sys.argv[1:]) + " Foo!")
if __name__ == "__main__":
    main()