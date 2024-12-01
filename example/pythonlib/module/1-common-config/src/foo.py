from jinja2 import Template


class Foo:
    def value(self, text: str):
        """Generates an HTML template with dynamic content."""
        template = Template("<h1>{{ text }}</h1>")
        return template.render(text=text)

    def main(self):
        print(f"Foo.value: {self.value('hello')}")


if __name__ == "__main__":
    Foo().main()
