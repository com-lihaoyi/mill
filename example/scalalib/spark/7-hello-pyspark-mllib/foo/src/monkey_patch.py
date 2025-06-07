# monkey_patch.py
import sys

# Patch distutils.version.LooseVersion if not available
try:
    from distutils.version import LooseVersion
except ModuleNotFoundError:
    from packaging.version import Version
    class LooseVersion(str):
        def __init__(self, v):
            self._v = Version(v)
        def __lt__(self, other):
            return self._v < Version(other)
        def __le__(self, other):
            return self._v <= Version(other)
        def __eq__(self, other):
            return self._v == Version(other)
        def __ne__(self, other):
            return self._v != Version(other)
        def __gt__(self, other):
            return self._v > Version(other)
        def __ge__(self, other):
            return self._v >= Version(other)

    import types
    distutils = types.ModuleType("distutils")
    version = types.ModuleType("distutils.version")
    version.LooseVersion = LooseVersion
    distutils.version = version
    sys.modules["distutils"] = distutils
    sys.modules["distutils.version"] = version