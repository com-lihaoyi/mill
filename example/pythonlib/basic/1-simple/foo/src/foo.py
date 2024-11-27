import argparse
from argparse import Namespace
from jinja2 import Template


def generate_html(text: str) -> str:
    template = Template("<h1>{{ text }}</h1>")
    return template.render(text=text)


def main() -> str:
    parser = argparse.ArgumentParser(description="Inserts text into an HTML template")
    parser.add_argument("-t", "--text", required=True, help="Text to insert")

    args: Namespace = parser.parse_args()
    html: str = generate_html(args.text)
    return html


if __name__ == "__main__":
    print(main())
