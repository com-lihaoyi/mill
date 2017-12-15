import json
import os
from random import *
import string
import sys

constants = [True, False, None]

def mkconstant():
    return choice(constants)

def mkinteger():
    return randint(-1e3, 1e3) * (10 ** normalvariate(0, 4)) + randint(-1e3, 1e3)

def mkdouble():
    return random() * (10 ** normalvariate(0, 30))

def mknum():
    if randint(0, 1):
        return mkdouble()
    else:
        return mkinteger()

def mkstring():
    n = int(min(abs(normalvariate(40, 20)), abs(normalvariate(30, 10))))
    return ''.join([choice(string.ascii_letters) for i in range(0, n)])

values = [mkconstant, mknum, mknum, mknum, mkstring]

def mkvalue():
    return choice(values)()

if __name__ == "__main__":
    args = sys.argv[1:]
    try:
        num = int(args[0])
        path = args[1]
        print "writing json (%d rows) into %s" % (num, path)
        f = open(path, 'w')
        f.write("[")
        for i in range(0, num):
            if i > 0: f.write(", ")
            c = {"foo": mkstring(),
                 "bar": mknum(),
                 "qux": mkvalue(),
                 "duh": {"a": mknum(), "b": mknum(), "c": mknum()},
                 "xyz": {"yy": mkstring(), "zz": mkvalue()},
                 "abc": [mkvalue() for i in range(0, 4)]}
            f.write(json.dumps(c))
        f.write("]")
        f.close()
    except Exception, e:
        print "usage: %s NUM PATH" % sys.argv[0]
