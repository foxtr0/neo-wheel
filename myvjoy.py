import ctypes, os, struct

HID_X, HID_Y, HID_Z = 0x30, 0x31, 0x32
HID_RX, HID_RY, HID_RZ = 0x33, 0x34, 0x35
HID_SL0, HID_SL1 = 0x36, 0x37

class VJoy:
    def __init__(self, device_id=1, dll_name="vJoyInterface.dll"):
        if struct.calcsize("P") * 8 != 64:
            print("WARNING: 32-bit Python, you need the x86 DLL")
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), dll_name)
        if not os.path.exists(path):
            raise FileNotFoundError(path)
        self.dll = ctypes.CDLL(path)
        d = self.dll
        d.vJoyEnabled.restype = ctypes.c_bool
        d.GetVJDStatus.argtypes = [ctypes.c_uint]
        d.AcquireVJD.argtypes = [ctypes.c_uint]
        d.AcquireVJD.restype = ctypes.c_bool
        d.RelinquishVJD.argtypes = [ctypes.c_uint]
        d.ResetVJD.argtypes = [ctypes.c_uint]
        d.SetAxis.argtypes = [ctypes.c_long, ctypes.c_uint, ctypes.c_uint]
        d.SetAxis.restype = ctypes.c_bool
        d.SetBtn.argtypes = [ctypes.c_bool, ctypes.c_uint, ctypes.c_ubyte]
        d.SetBtn.restype = ctypes.c_bool

        if not d.vJoyEnabled():
            raise RuntimeError("vJoy driver not installed or not enabled")
        self.id = device_id
        status = d.GetVJDStatus(device_id)   # 0 own, 1 free, 2 busy, 3 missing, 4 unknown
        if status == 2:
            raise RuntimeError("Device %d is used by another program" % device_id)
        if status in (3, 4):
            raise RuntimeError("Device %d is not configured (use Configure vJoy)" % device_id)
        if status not in (0, 1):
            raise RuntimeError("Unexpected status %d" % status)
        if status == 1 and not d.AcquireVJD(device_id):
            raise RuntimeError("Could not acquire device %d" % device_id)
        d.ResetVJD(device_id)

    def axis(self, usage, value01):
        """usage = HID_X etc., value01 = 0.0 .. 1.0"""
        v = int(max(0.0, min(1.0, value01)) * 0x7FFF) + 1
        return self.dll.SetAxis(v, self.id, usage)

    def button(self, n, pressed):
        return self.dll.SetBtn(bool(pressed), self.id, n)

    def close(self):
        self.dll.RelinquishVJD(self.id)