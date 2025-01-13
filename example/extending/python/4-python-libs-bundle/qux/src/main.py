#!/usr/bin/python3
import numpy as np

from foo.src.foo import data
from foo.bar.src.bar import df

def main() -> None:
    print(f"Numpy : Sum: {np.sum(data)} | Pandas: Mean: {df['Values'].mean()}, Max: {df['Values'].max()}")

if __name__ == "__main__":
    main()