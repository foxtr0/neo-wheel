import socket, mmap, ctypes, threading, time
from myvjoy import VJoy, HID_X, HID_Y, HID_Z

# ---------- Assetto Corsa shared memory layouts ----------
class Physics(ctypes.Structure):
    _pack_ = 4
    _fields_ = [
        ("packetId", ctypes.c_int), ("gas", ctypes.c_float), ("brake", ctypes.c_float),
        ("fuel", ctypes.c_float), ("gear", ctypes.c_int), ("rpms", ctypes.c_float),
        ("steerAngle", ctypes.c_float), ("speedKmh", ctypes.c_float),
        ("velocity", ctypes.c_float * 3), ("accG", ctypes.c_float * 3),
        ("wheelSlip", ctypes.c_float * 4), ("wheelLoad", ctypes.c_float * 4),
        ("wheelsPressure", ctypes.c_float * 4), ("wheelAngularSpeed", ctypes.c_float * 4),
        ("tyreWear", ctypes.c_float * 4), ("tyreDirtyLevel", ctypes.c_float * 4),
        ("tyreCoreTemperature", ctypes.c_float * 4), ("camberRAD", ctypes.c_float * 4),
        ("suspensionTravel", ctypes.c_float * 4), ("drs", ctypes.c_float),
        ("tc", ctypes.c_float), ("heading", ctypes.c_float), ("pitch", ctypes.c_float),
        ("roll", ctypes.c_float), ("cgHeight", ctypes.c_float),
        ("carDamage", ctypes.c_float * 5), ("numberOfTyresOut", ctypes.c_int),
        ("pitLimiterOn", ctypes.c_int), ("abs", ctypes.c_float),
    ]

class Static(ctypes.Structure):
    _pack_ = 4
    _fields_ = [
        ("smVersion", ctypes.c_wchar * 15), ("acVersion", ctypes.c_wchar * 15),
        ("numberOfSessions", ctypes.c_int), ("numCars", ctypes.c_int),
        ("carModel", ctypes.c_wchar * 33), ("track", ctypes.c_wchar * 33),
        ("playerName", ctypes.c_wchar * 33), ("playerSurname", ctypes.c_wchar * 33),
        ("playerNick", ctypes.c_wchar * 33), ("sectorCount", ctypes.c_int),
        ("maxTorque", ctypes.c_float), ("maxPower", ctypes.c_float), ("maxRpm", ctypes.c_int),
    ]

def open_map(name, struct):
    size = ctypes.sizeof(struct)
    return mmap.mmap(-1, size, name, access=mmap.ACCESS_READ), size

def read_map(mm, size, struct):
    mm.seek(0)
    return struct.from_buffer_copy(mm.read(size))

# ---------- shared state ----------
phone = {"ip": None}
DEBUG = False   # set True to print telemetry values

def clamp01(x):
    return max(0.0, min(1.0, x))

# ---------- telemetry -> haptics thread ----------
def telemetry_thread():
    out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    phys = stat = None
    maxrpm = 8000.0
    last_gear = None
    last_pid = -1
    last_pid_time = time.time()
    last_g = 0.0
    bump_until = 0.0
    impact_until = 0.0

    def send(h):
        if phone["ip"]:
            try:
                out.sendto(("%.3f" % h).encode(), (phone["ip"], 5006))
            except OSError:
                pass

    while True:
        time.sleep(1 / 60)
        now = time.time()

        # (re)connect to AC shared memory
        if phys is None:
            try:
                phys = open_map("acpmf_physics", Physics)
                stat = open_map("acpmf_static", Static)
                print("Connected to Assetto Corsa")
            except Exception:
                phys = stat = None
                send(0)
                time.sleep(1)
                continue

        try:
            p = read_map(phys[0], phys[1], Physics)
            if maxrpm == 8000.0:
                s = read_map(stat[0], stat[1], Static)
                if s.maxRpm > 1000:
                    maxrpm = float(s.maxRpm)
        except Exception:
            phys = stat = None
            continue

        # paused / menu: AC stops updating packetId
        if p.packetId != last_pid:
            last_pid = p.packetId
            last_pid_time = now
        if now - last_pid_time > 0.5:
            send(0)
            continue

        h = 0.0
        ratio = clamp01(p.rpms / maxrpm) if maxrpm else 0.0

        # engine rumble grows with RPM, strong near the limiter
        if p.rpms > 300:
            h = 0.10 + 0.30 * ratio ** 2
        if ratio > 0.93:
            h = max(h, 0.75)

        # tyre slip (tune the divisor if it feels too weak/strong)
        slip = max(p.wheelSlip)
        if slip > 2.0:
            h = max(h, min(0.8, slip / 10.0))

        # ABS / traction control pulsing
        if p.abs > 0.1 or p.tc > 0.1:
            h = max(h, 0.55 if int(now * 25) % 2 == 0 else 0.25)

        # gear shift thump
        if last_gear is not None and p.gear != last_gear:
            bump_until = now + 0.12
        last_gear = p.gear
        if now < bump_until:
            h = max(h, 0.9)

        # impacts: sudden jump in total G
        g = (p.accG[0] ** 2 + p.accG[1] ** 2 + p.accG[2] ** 2) ** 0.5
        if abs(g - last_g) > 2.0:
            impact_until = now + 0.25
        last_g = g
        if now < impact_until:
            h = 1.0

        if DEBUG:
            print("rpm %5.0f/%d gear %d slip %.2f abs %.2f tc %.2f G %.2f -> %.2f"
                  % (p.rpms, maxrpm, p.gear, slip, p.abs, p.tc, g, h))
        send(clamp01(h))

threading.Thread(target=telemetry_thread, daemon=True).start()

# ---------- vJoy input from phone ----------
j = VJoy(1)
j.axis(HID_X, 0.5); j.axis(HID_Y, 0); j.axis(HID_Z, 0)

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("0.0.0.0", 5005))
s.settimeout(1.0)
print("Listening on UDP 5005")

while True:
    try:
        data, addr = s.recvfrom(256)
        phone["ip"] = addr[0]            # remember the phone for haptics
        steer, gas, brake = (float(x) for x in data.decode().split(","))
        j.axis(HID_X, (steer + 1) / 2)
        j.axis(HID_Y, gas)
        j.axis(HID_Z, brake)
    except socket.timeout:
        j.axis(HID_X, 0.5); j.axis(HID_Y, 0); j.axis(HID_Z, 0)
    except ValueError:
        pass