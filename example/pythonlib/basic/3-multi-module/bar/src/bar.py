import sys
from jinja2 import Template
from markupsafe import escape


def generate_html(bar_text):
    escaped_text = escape(bar_text)
    template = Template("<h1>{{ text }}</h1>")
    return template.render(text=escaped_text)


if __name__ == "__main__":
    # Get the argument from command line
    text = sys.argv[1]
    print(generate_html(text))
