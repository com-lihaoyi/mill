import argparse
import os
from myDeps import MyDeps # type: ignore


class Foo:
    def main(self, text: str) -> None:
        print("text: ", text)
        print("MyDeps.value: ", MyDeps.value)
        print("My_Line_Count: ", os.environ.get("MY_LINE_COUNT"))

if __name__  == '__main__':
    # Create the argument parser
    parser = argparse.ArgumentParser(description="Process text argument")

    # Add argument for text
    parser.add_argument("--text", type=str, required=True, help="Text for printing")

    # Parse the arguments
    args = parser.parse_args()
    
    Foo().main(args.text)
