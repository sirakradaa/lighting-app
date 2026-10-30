# Lights

An Android app for the Bluetooth lamps that ship with **KS Light** and **LED LAMP**. The phone talks to the lamps directly. Nothing is sent to the companies behind those apps.

Close KS Light and LED LAMP before connecting. A lamp accepts one Bluetooth connection at a time.

(I really just made this because I was tired of having to use 2 different apps for the lights in my room.)

## Install

The debug build is `Lights.apk` in this folder. Copy it to the phone and open it. Android will ask you to allow install from this source.

Or, with the phone plugged in and USB debugging on:

```bat
adb install -r Lights.apk
```

To build it again, from the `android` folder:

```bat
gradlew.bat assembleDebug
```

The new APK is `android\app\build\outputs\apk\debug\app-debug.apk`.

## Which lamp is which

| Bluetooth name | Type in the app |
| --- | --- |
| `KS03~…` | KS Light, newer |
| `KS01`, `KS02`, `KS03-`, `KS04`, other `KS…` | KS Light, older |
| `LEDDMX…`, `LEDCAR…` | LED LAMP strip |
| `LEDBLE…`, `LEDSTAGE…`, `LEDLIGHT…` | LED LAMP controller |
| `YouOKLight`, `iStrip`, `YH-…`, or a lamp advertising iStrip+ | YouOKLight sunset |

Scan guesses from the name. If a lamp is unrecognized, pick the type on its card before connecting. The sunset lamp is the YouOKLight type. Close the iStrip+ app before connecting. If the hue is wrong, set Color order to GRB.

The COPU glowing ball uses an infrared remote and a button on the lamp. This phone cannot send infrared, so that ball stays on its remote.

If green and blue are swapped on an LEDDMX strip, set Color order to RBG. If an LEDDMX lamp ignores power, switch Power commands to Alternate.

`LEDDMX-02`, `LEDDMX-04`, and `LEDCAR-02` sometimes use a shifted frame this app does not send. Wi-Fi-only controllers will not appear in a Bluetooth scan.
