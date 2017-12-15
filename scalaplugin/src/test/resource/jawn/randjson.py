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

def mkarray(n, t, threshold):
    a = []
    t2 = t + random()
    if (t > threshold):
        for i in range(0, 2 * n):
            a.append(mkvalue())
    else:
        #print "mkarray(%s, %s, %s)" % (n, t, threshold)
        for i in range(0, n / 5):
            a.append(mkcontainer(t2, threshold))
    return a

def mkobject(n, t, threshold):
    d = {}
    t2 = t + random()
    if (t > threshold):
        for i in range(0, n):
            k = mkstring()
            v = mkvalue()
            d[k] = v
    else:
        #print "mkobject(%s, %s, %s)" % (n, t, threshold)
        for i in range(0, n / 10):
            k = mkstring()
            v = mkcontainer(t2, threshold)
            d[k] = v
    return d

containers = [mkarray, mkobject, mkobject]

def mkcontainer(t, threshold):
    n = int(abs(normalvariate(10, 30)))
    return choice(containers)(n, t, threshold)

if __name__ == "__main__":
    args = sys.argv[1:]
    try:
        weight = float(args[0])
        path = args[1]
        print "generating random JSON with weight %s into %s" % (weight, path)
        f = open(path, 'w')
        c = mkcontainer(0.0, weight)
        f.write(json.dumps(c))
        f.close()
    except:
        print "usage: %s WEIGHT (0.0 < w < ~4.0) FILE" % sys.argv[0]
