import sys

class User:
    def __init__(self, first_name: str, last_name: str, role: str):
        self.first_name = first_name
        self.last_name = last_name
        self.role = role

user = User(first_name=sys.argv[1], last_name=sys.argv[2], role="Professor")

print("Hello " + user.first_name + " " + user.last_name)
