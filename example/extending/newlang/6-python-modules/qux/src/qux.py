import sys

def main() -> None:
    # Get command-line arguments, skipping the first one (script name)
    args = sys.argv[1:]

    # Check if any arguments were provided
    if not args:
        print("Hello, World Qux!")
    else:
        # Join the arguments with spaces and print the greeting
        greeting = "Hello, " + " ".join(args) + " Qux!"
        print(greeting)

if __name__ == "__main__":
    main()
