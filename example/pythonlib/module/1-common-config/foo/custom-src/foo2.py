import os
import importlib.resources
from jinja2 import Template
from foo import Foo  # type: ignore
from fooA import FooA  # type: ignore
from fooB import FooB  # type: ignore
from fooC import FooC  # type: ignore


class Foo2:

    def value(self, text: str):
        """Generates an HTML template with dynamic content."""
        template = Template("<h1>{{ text }}</h1>")
        return template.render(text=text)

    def read_resource(self, package: str, resource_name: str) -> str:
        """Reads the content of a resource file."""
        try:
            resource_content = (
                importlib.resources.files(package).joinpath(resource_name).read_text()
            )
            return resource_content.strip()
        except FileNotFoundError:
            return f"Resource '{resource_name}' not found."

    def main(self):
        # Output for value()
        print(f"Foo2.value: {self.value('hello2')}")
        print(f"Foo.value: {Foo().value('hello')}")
        print(f"FooA.value: {FooA.value}")
        print(f"FooB.value: {FooB.value}")
        print(f"FooC.value: {FooC.value}")

        # Reading resources
        print(f"MyResource: {self.read_resource('res', 'MyResource.txt')}")
        print(
            f"MyOtherResource: {self.read_resource('custom-res', 'MyOtherResources.txt')}"
        )

        # Accessing environment variable
        my_custom_env = os.environ.get("MY_CUSTOM_ENV")
        if my_custom_env:
            print(f"MY_CUSTOM_ENV: {my_custom_env}")


if __name__ == "__main__":
    Foo2().main()
