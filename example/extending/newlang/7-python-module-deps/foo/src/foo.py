import numpy as np

def numpy_example():
    """Example using NumPy: Create an array and perform basic operations."""
    array = np.array([1, 2, 3, 4, 5])
    return(f"NumPy Example: Array: {array} ; Mean: {np.mean(array)} ; Sum: {np.sum(array)}")


def main():
    return(numpy_example())

if __name__ == "__main__":
    main()
