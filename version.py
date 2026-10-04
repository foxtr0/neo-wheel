import ctypes, os, pyvjoy

# Load the same DLL pyvjoy uses
dll_path = os.path.join(os.path.dirname(pyvjoy.__file__), "vJoyInterface.dll")
print("Using DLL:", dll_path)
dll = ctypes.WinDLL(dll_path)

print("vJoy enabled:", bool(dll.vJoyEnabled()))

ver = ctypes.c_ushort()
dll.DriverMatch.restype = ctypes.c_bool
dll.DriverMatch.argtypes = [ctypes.POINTER(ctypes.c_ushort), ctypes.POINTER(ctypes.c_ushort)]
dll_ver = ctypes.c_ushort()
drv_ver = ctypes.c_ushort()
match = dll.DriverMatch(ctypes.byref(dll_ver), ctypes.byref(drv_ver))

print("DLL version   : 0x%04X" % dll_ver.value)
print("Driver version: 0x%04X" % drv_ver.value)
print("Match:", match)

dll.GetVJDStatus.argtypes = [ctypes.c_uint]
print("Device 1 status:", dll.GetVJDStatus(1))