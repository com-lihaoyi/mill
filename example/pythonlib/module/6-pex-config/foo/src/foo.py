import pyfiglet
from termcolor import colored
import argparse

class Foo:
    def main(self, text: str) -> str:
        ascii_art = pyfiglet.figlet_format(text)
        colored_art = colored(ascii_art, "cyan")
        return colored_art

if __name__ == "__main__":
    # Create the argument parser
    parser = argparse.ArgumentParser(description="Process text argument")

    # Add argument for text
    parser.add_argument("--text", type=str, required=True, help="Text for printing")

    # Parse the arguments
    args = parser.parse_args()
    
    print(Foo().main(args.text))
