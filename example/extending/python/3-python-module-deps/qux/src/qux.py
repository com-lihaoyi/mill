from foo.bar.src.bar import add # type: ignore
from foo.src.foo import multiply # type: ignore
import sys
def divide(a: int, b: int) -> float: return a/b
def main() -> None:
    x = int(sys.argv[1])
    y = int(sys.argv[2])
    print(f"Add: {x} + {y} = {add(x, y)} | Multiply: {x} * {y} = {multiply(x, y)} | Divide: {x} / {y} = {divide(x, y)}")
if __name__ == "__main__":
    main()