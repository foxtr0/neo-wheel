from myvjoy import *
import time
j = VJoy(1)
for u in (HID_X, HID_Y, HID_Z):
    for v in (0, 0.5, 1, 0):
        j.axis(u, v); time.sleep(0.5)
j.close()