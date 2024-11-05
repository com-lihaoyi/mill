import sys

def add(a: int, b: int) -> int:
    """Adds two integers."""
    return a + b

def main() -> None:
    # Get command-line arguments, skipping the first one (script name)
    args = sys.argv[1:]

    # Example of using the add function with incorrect types
    # Uncomment this line to see the error
    # result = add("5", 10)  # This will cause a TypeError, handled in the function
    
    # Check if any arguments were provided
    if not args:
        print("Hello, World!")
    else:
        # Join the arguments with spaces and print the greeting
        greeting = "Hello, " + " ".join(args) + "!"
        print(greeting)

if __name__ == "__main__":
    main()
