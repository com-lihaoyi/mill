import sys
def main() -> None: print("Hello, " + " ".join(sys.argv[1:]) + " Qux!")
if __name__ == "__main__":
    main()