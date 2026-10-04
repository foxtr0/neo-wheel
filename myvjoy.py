import ctypes, os, struct, winreg

import shutil


def _vjoy_dirs_from_registry():
    base = r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"
    for view in (winreg.KEY_WOW64_64KEY, winreg.KEY_WOW64_32KEY):
        try:
            root = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, base, 0,
                                  winreg.KEY_READ | view)
        except OSError:
            continue
        with root:
            i = 0
            while True:
                try:
                    name = winreg.EnumKey(root, i)
                except OSError:
                    break
                i += 1
                try:
                    with winreg.OpenKey(root, name) as k:
                        try:
                            disp = winreg.QueryValueEx(k, "DisplayName")[0]
                        except OSError:
                            continue
                        if "vjoy" not in str(disp).lower():
                            continue
                        for field in ("InstallLocation", "Inno Setup: App Path"):
                            try:
                                yield winreg.QueryValueEx(k, field)[0]
                            except OSError:
                                pass
                        try:   # fall back to the folder holding the uninstaller
                            u = winreg.QueryValueEx(k, "UninstallString")[0]
                            yield os.path.dirname(u.strip('"'))
                        except OSError:
                            pass
                except OSError:
                    continue

def find_installed_dll(dll_name="vJoyInterface.dll"):
    """Locate the DLL in the user's vJoy install, matching Python's bitness."""
    arch = "x64" if struct.calcsize("P") * 8 == 64 else "x86"

    dirs = list(_vjoy_dirs_from_registry())                       # 1) registry
    for env in ("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)"):   # 2) default paths
        root = os.environ.get(env)
        if root:
            dirs.append(os.path.join(root, "vJoy"))

    for d in dirs:
        d = d.strip().strip('"')
        for p in (os.path.join(d, arch, dll_name), os.path.join(d, dll_name)):
            if os.path.exists(p):
                return p
    return None

def ensure_dll(folder, dll_name="vJoyInterface.dll"):
    local = os.path.join(folder, dll_name)
    src = find_installed_dll(dll_name)
    if src is None:
        if os.path.exists(local):
            return local           
        raise FileNotFoundError(
            "vJoy not found. Install vJoy, or place vJoyInterface.dll next to myvjoy.py")
    try:
        if (not os.path.exists(local)
                or os.path.getsize(local) != os.path.getsize(src)
                or os.path.getmtime(local) < os.path.getmtime(src)):
            shutil.copy2(src, local)
            print("Copied vJoyInterface.dll from", src)
    except OSError as e:
        print("Could not copy DLL (%s), using existing copy" % e)
    return local



HID_X, HID_Y, HID_Z = 0x30, 0x31, 0x32
HID_RX, HID_RY, HID_RZ = 0x33, 0x34, 0x35
HID_SL0, HID_SL1 = 0x36, 0x37

class VJoy:
    def __init__(self, device_id=1, dll_name="vJoyInterface.dll"):
        if struct.calcsize("P") * 8 != 64:
            print("WARNING: 32-bit Python, you need the x86 DLL")
        path = ensure_dll(os.path.dirname(os.path.abspath(__file__)), dll_name)
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