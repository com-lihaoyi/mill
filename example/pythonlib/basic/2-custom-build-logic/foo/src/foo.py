import importlib.resources


def line_count() -> int:
    resource_content = (importlib.resources.files("resources").joinpath("line-count.txt").read_text())
    return int(resource_content.strip())


if __name__ == "__main__":
    print(f"Line Count: {line_count()}")
