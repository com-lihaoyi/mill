from jinja2 import Template


def get_value(text: str) -> str:
    template = Template("<h1>{{ text | safe }}</h1>")
    return template.render(text=text)


if __name__ == "__main__":
    print(get_value(text="<XYZ>"))
