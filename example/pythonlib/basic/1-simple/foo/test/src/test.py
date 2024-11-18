import unittest
import numpy as np
import pandas as pd
from foo.src.main import add, main

class TestScript(unittest.TestCase):
    def test_add(self) -> None:
        # Test the add function
        self.assertEqual(add(2, 3), 5)
        self.assertEqual(add(-1, 1), 0)
        self.assertEqual(add(0, 0), 0)

    def test_main(self) -> None:
        # Test the main function output
        data = np.array([100, 200, 300, 400, 500])
        df = pd.DataFrame({"Values": data})
        expected_output = "Numpy : Sum: 1500 | Pandas: Mean: 300.0, Max: 500"
        self.assertEqual(main(data, df), expected_output)
        
        data = np.array([10, 20, 30, 40, 50])
        df = pd.DataFrame({"Values": data})
        expected_output = "Numpy : Sum: 150 | Pandas: Mean: 30.0, Max: 50"
        self.assertEqual(main(data, df), expected_output)

if __name__ == "__main__":
    unittest.main()
