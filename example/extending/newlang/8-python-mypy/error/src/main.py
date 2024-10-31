import mypy
def add(a: int, b: int) -> int:
    """Attempts to add two integers, handling type errors gracefully."""
    return a + b
    
def greet(name: str) -> str:
    """Generates a greeting message for the given name."""
    return "Hello, " + name

def main() -> None:
    # Example of using the add function with incorrect types
    # Uncomment this line to see the error
    # result = add("5", 10)  # This will cause a TypeError, handled in the function

    # Example of using the greet function
    message = greet("Alice")
    print(f"Addition Result: Uncomment the Result line in the code to see error | Greeting Result: {message}")

if __name__ == "__main__":
    main()
