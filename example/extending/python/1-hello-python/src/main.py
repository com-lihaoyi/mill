import sys
def add(a: int, b: int) -> int: return a + b
def main() -> None: print("Hello, " + " ".join(sys.argv[1:]) + "!")
if __name__ == "__main__":
    main()
    print(add(5, 10)) # Error Example: add("5", 10) will cause a TypeError