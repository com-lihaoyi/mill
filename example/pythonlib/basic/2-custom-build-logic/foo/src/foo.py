import os


def line_count() -> int:
    file_path = next(
        (
            os.path.join(p, "line-count.txt")
            for p in os.environ.get("PYTHONPATH", "").split(":")
            if os.path.exists(os.path.join(p, "line-count.txt"))
        ),
        None,
    )

    if file_path:
        with open(file_path) as f:
            return int(f.readline())
    return -1


if __name__ == "__main__":
    print(f"Line Count: {line_count()}")
