import importlib.resources


class Foo:
    def PythonPathResourceText(self, package, resourceName: str) -> None:
        resource_content = (
            importlib.resources.files(package).joinpath(resourceName).read_text()
        )
        return resource_content.strip()


if __name__ == "__main__":
    print(Foo().PythonPathResourceText("res", "file.txt"))
