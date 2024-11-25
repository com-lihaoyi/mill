import argparse
from bar import generate_html  # type: ignore


def main(foo_text: str, bar_text: str) -> str:
    # Print the values of foo-text and bar-text
    return f"Foo.value: {foo_text}\nBar.value: {generate_html(bar_text)}"


if __name__ == "__main__":
    # Create the argument parser
    parser = argparse.ArgumentParser(description="Process two text arguments")

    # Add arguments for foo-text and bar-text
    parser.add_argument("--foo-text", type=str, required=True, help="Text for foo")
    parser.add_argument("--bar-text", type=str, required=True, help="Text for bar")

    # Parse the arguments
    args = parser.parse_args()

    # run the main function with given arguments
    print(main(args.foo_text, args.bar_text))
