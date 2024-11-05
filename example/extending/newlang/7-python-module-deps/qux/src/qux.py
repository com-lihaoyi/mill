from foo.bar.src.bar import add # type: ignore
from foo.src.foo import multiply # type: ignore
import sys

def divide(a: int, b: int) -> float:
    """Returns the division of two integers."""
    if b == 0:
        raise ValueError("Division by zero is not allowed.")
    return a / b

def main() -> None:
    if len(sys.argv) != 3:
        print("Usage: python main.py <num1> <num2>")
        sys.exit(1)

    try:
        x = int(sys.argv[1])
        y = int(sys.argv[2])
    except ValueError:
        print("Please provide two integers as input.")
        sys.exit(1)

    print(f"Add: {x} + {y} = {add(x, y)} | Multiply: {x} * {y} = {multiply(x, y)} | Divide: {x} / {y} = {divide(x, y)}")

if __name__ == "__main__":
    main()