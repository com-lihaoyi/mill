import importlib.resources


def line_count() -> int:
    with importlib.resources.open_text("resources", "line-count.txt") as file:
        return int(file.readline().strip())


if __name__ == "__main__":
    print(f"Line Count: {line_count()}")
