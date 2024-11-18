import sys


def combine(name: str) -> str:
    return f"Hello {name}"


if __name__ == "__main__":
    # Get the argument from command line
    name = sys.argv[1]
    print(combine(name))
