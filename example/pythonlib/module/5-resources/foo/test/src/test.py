import os
from pathlib import Path
import importlib.resources
import unittest
from foo import Foo  # type: ignore


class TestScript(unittest.TestCase):
    def test_all(self) -> None:
        appPythonPathResourceText = Foo().PythonPathResourceText("resources", "file.txt")
        self.assertEqual(appPythonPathResourceText, "Hello World Resource File")

        testPythonPathResourceText = (
            importlib.resources.files("resources")
            .joinpath("test-file-a.txt")
            .read_text()
            .strip()
        )
        self.assertEqual(testPythonPathResourceText, "Test Hello World Resource File A")

        testFileResourceFile = next(
            Path(os.getenv("MILL_TEST_RESOURCE_DIR")).rglob("test-file-b.txt"), None
        )
        with open(testFileResourceFile, "r", encoding="utf-8") as file:
            testFileResourceText = file.readline()
        self.assertEqual(testFileResourceText, "Test Hello World Resource File B")

        with open(
            Path(os.getenv("OTHER_FILES_DIR"), "other-file.txt"), "r", encoding="utf-8"
        ) as file:
            otherFileText = file.readline()
        self.assertEqual(otherFileText, "Other Hello World File")


if __name__ == "__main__":
    unittest.main()
