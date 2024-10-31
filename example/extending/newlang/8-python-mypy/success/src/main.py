import mypy

def add(a: int, b: int) -> int:
    """Adds two integers."""
    return a + b

def greet(name: str) -> str:
    """Generates a greeting message for the given name."""
    return "Hello, " + name

def main() -> None:
    # Example of using the add function
    result = add(5, 10)

    # Example of using the greet function
    message = greet("Alice")
    print(f"Addition Result: {result} | Greeting Result: {message}")

if __name__ == "__main__":
    main()