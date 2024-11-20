import argparse
from bar import generate_html


def main():
    # Create the argument parser
    parser = argparse.ArgumentParser(description="Process two text arguments")

    # Add arguments for foo-text and bar-text
    parser.add_argument("--foo-text", type=str, required=True, help="Text for foo")
    parser.add_argument("--bar-text", type=str, required=True, help="Text for bar")

    # Parse the arguments
    args = parser.parse_args()

    # Print the values of foo-text and bar-text
    return f"Foo.value: {args.foo_text}\nBar.value: {generate_html(args.bar_text)}"


if __name__ == "__main__":
    print(main())
