import sys
import time
import fcntl

def main():
    with open(sys.argv[1], 'a+') as file:
        fcntl.flock(file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        time.sleep(9999)

if __name__ == "__main__":
    main()

