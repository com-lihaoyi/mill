from pathlib import Path
import sys


def line_count() -> int:
    for path in sys.path:
        file_path = Path(path) / "line-count.txt"
        if file_path.exists():
            return int(file_path.read_text().splitlines()[0])
    return -1


if __name__ == "__main__":
    print(f"Line Count: {line_count()}")
