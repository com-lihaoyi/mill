import pyfiglet
from termcolor import colored


def get_value() -> str:
    ascii_art = pyfiglet.figlet_format("XYZ")
    colored_art = colored(ascii_art, "cyan")
    return colored_art


if __name__ == "__main__":
    print(get_value())
