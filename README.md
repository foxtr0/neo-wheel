# Neo 

Use your Android phone as a steering wheel for PC racing games.
Tilt to steer, analog touch pedals, assignable buttons, volume keys as
buttons, and haptic feedback from Assetto Corsa telemetry.

## Features
- Tilt steering (gyro-fused), adjustable lock angle, smoothing, deadzone
- Analog gas and brake (slide your finger up the pedal)
- Up to 8 assignable on-screen buttons, optional toggle mode
- Volume keys usable as buttons (hides the volume popup)
- Haptics: engine, redline, tyre slip, ABS/TC, shifts, impacts

## PC setup (Windows)
1. Install [vJoy](https://github.com/jshafer817/vJoy) and open **Configure vJoy**:
   enable Device 1 with X, Y, Z axes and **32 buttons**.
2. Install 64-bit Python 3.8+. No extra packages are needed.
3. Run `python server.py`.
   `vJoyInterface.dll` is copied automatically from your vJoy install.
4. Allow Python through the Windows Firewall on **private** networks (UDP 5005).

## Phone setup
1. Download the APK from [Releases](../../releases) and install it
   (allow "install unknown apps").
2. Enter your PC's IP (`ipconfig`), tap **Set IP**.
3. Hold the phone level like a wheel and tap **Center**.
4. In your game, bind steering to vJoy X, throttle to Y, brake to Z,
   and buttons by pressing them when the game asks.

Phone and PC must be on the same network. A phone hotspot or USB tethering
gives the lowest latency.

## Haptics
Haptics are currently supported only for Assetto Corsa / Assetto Corsa Competizione.


## Credits
Uses the [vJoy](https://github.com/jshafer817/vJoy) virtual joystick driver (MIT).

