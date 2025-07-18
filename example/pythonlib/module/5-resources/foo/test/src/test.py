import os
from pathlib import Path
import importlib.resources
import unittest
from foo import Foo  # type: ignore


class TestScript(unittest.TestCase):
    def test_all(self) -> None:
        appPythonPathResourceText = Foo().PythonPathResourceText("res", "file.txt")
        self.assertEqual(appPythonPathResourceText, "Hello World Resource File")

        testPythonPathResourceText = (
            importlib.resources.files("res")
            .joinpath("test-file-a.txt")
            .read_text()
            .strip()
        )
        self.assertEqual(testPythonPathResourceText, "Test Hello World Resource File A")

        with open(
            Path(os.getenv("OTHER_FILES_DIR"), "other-file.txt"), "r", encoding="utf-8"
        ) as file:
            otherFileText = file.readline()
        self.assertEqual(otherFileText, "Other Hello World File")


if __name__ == "__main__":
    unittest.main()
