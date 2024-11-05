import numpy as np
import pandas as pd # type: ignore

def main() -> None:
    """ Prints Sum, mean and max using numpy and pandas library """
    data = np.array([10, 20, 30, 40, 50])
    df = pd.DataFrame({"Values": data})
    print(f"Numpy : Sum: {np.sum(data)} | Pandas: Mean: {df['Values'].mean()}, Max: {df['Values'].max()}")

if __name__ == "__main__":
    main()
