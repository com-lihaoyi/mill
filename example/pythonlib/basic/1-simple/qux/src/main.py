#!/usr/bin/python3
import numpy as np

from foo import data
from bar import df

def main() -> None:
    print(f"Numpy : Sum: {np.sum(data)} | Pandas: Mean: {df['Values'].mean()}, Max: {df['Values'].max()}")

if __name__ == "__main__":
    main()
