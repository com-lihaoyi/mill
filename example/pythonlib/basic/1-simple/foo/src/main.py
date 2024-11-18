#!/usr/bin/python3
import numpy as np
from numpy.typing import NDArray
import pandas as pd # type: ignore

def main(data: NDArray[np.float64], df: pd.DataFrame) -> str:
    return f"Numpy : Sum: {np.sum(data)} | Pandas: Mean: {df['Values'].mean()}, Max: {df['Values'].max()}"

def add(a: int, b: int) -> int:
    return a + b

if __name__ == "__main__":
    data = np.array([10, 20, 30, 40, 50])
    df = pd.DataFrame({"Values": data})

    print(main(data, df))
    print(add(5, 3))