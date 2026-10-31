Pocketfuse user guide
=====================

Pocketfuse turns your phone into the remote for your own FaceFusion PC. The PC does the work on its
GPU; the phone picks the faces, the target and the settings, and gets the result back. Nothing goes
through a cloud.

Contents

1. [What you need](#1-what-you-need)
2. [Set up the PC](#2-set-up-the-pc)
3. [Install the app and pair](#3-install-the-app-and-pair)
4. [Swap a photo or video](#4-swap-a-photo-or-video)
5. [Several files at once](#5-several-files-at-once)
6. [Lab: find the best setting](#6-lab-find-the-best-setting)
7. [Live: your camera, swapped](#7-live-your-camera-swapped)
8. [Dub: your voice, their lips](#8-dub-your-voice-their-lips)
9. [Puppet: bring a portrait to life](#9-puppet-bring-a-portrait-to-life)
10. [PC: what your computer is doing](#10-pc-what-your-computer-is-doing)
11. [Troubleshooting](#11-troubleshooting)
12. [Use it responsibly](#12-use-it-responsibly)


1. What you need
----------------

- A PC with an NVIDIA GPU (any recent card; tested on an RTX 3050 Laptop 6 GB) or a CPU-only PC (slow).
- [FaceFusion v4](https://github.com/facefusion/facefusion/tree/v4-challenge) installed on it, following
  the [installation guide](https://docs.facefusion.io/installation). Check it runs:
  `python facefusion.py --help` in the FaceFusion folder.
- An Android phone, Android 10 or newer (arm64).
- The phone and the PC on the same Wi-Fi, or both on the same [Tailscale](https://tailscale.com) tailnet
  (then it works from anywhere, on mobile data too).


2. Set up the PC
----------------

```
git clone https://github.com/AbrahamPaulJ/pocketfuse
cd pocketfuse/companion
pip install -r requirements.txt
python pocketfuse.py serve --facefusion-path <path to your facefusion folder>
```

The companion itself needs only Python (tested on 3.12 and 3.13) and three small packages. It starts FaceFusion with the
interpreter it runs on, so either run it from FaceFusion's own environment (its venv or conda env), or
point it there with `--python-path <FaceFusion's python>`. With an NVIDIA GPU add
`--execution-providers cuda` at the end; anything the companion does not know goes to FaceFusion.

`serve` picks your tailnet address (or your LAN address), starts the FaceFusion API there with a fresh
API key, waits until it answers and prints a QR code. Leave the window open: closing it stops the API.

- Same phone every day: set `FACEFUSION_API_KEY` to a long random string of your own before `serve`,
  and the phone stays paired across restarts.
- Windows asks once whether Python may accept connections on private networks: allow it, or the phone
  cannot reach the PC.
- Check a server end to end at any time: `python pocketfuse.py probe --source-path face.jpg --target-path clip.mp4`.


3. Install the app and pair
---------------------------

1. On the phone, download the `.apk` file from the
   [latest release](https://github.com/AbrahamPaulJ/pocketfuse/releases/latest) and open it. Android
   asks to allow installs from your browser once.
2. Open Pocketfuse and tap **Scan code**, then point it at the QR code in the PC window.
   No camera handy? Under **Or connect by hand**, type the address and the key that `serve` printed.
3. The top bar shows **Connected** with a green dot. Tap it at any time to see the PC tab.

The key is stored encrypted with the Android Keystore. **Forget this server** on the PC tab removes it.


4. Swap a photo or video
------------------------

1. **Faces**: tap **+** and pick a clear, front-facing photo of the face to use. The PC shows the face it
   found; a red "no face" badge means pick another photo. Several photos of the same person give a
   steadier result.
2. **Target**: tap **Pick**, or share a photo or video to Pocketfuse from your gallery, a chat or any app.
3. **Preview on PC**: one frame comes back in about a second. Drag the divider to compare before and
   after; the slider above picks which second of a video to preview.
4. **Options**: *Enhance face* sharpens the swapped face, *Keep expression* keeps the target's
   expression, *Main person* or *Everyone*, and the swap model. **All options** lists every setting your
   FaceFusion offers for the processors Pocketfuse runs, read live from the server. **Presets** saves the
   current options under a name.
5. **Swap on my PC**. You can lock the phone: the swap runs in the background with a notification, and
   the notification opens the result.
6. **Results** (the gallery icon, top right): play, **Save** to your gallery, **Share**, or **Delete**.

Every result carries an **AI GENERATED** label in the app. Keep it when you publish.


5. Several files at once
------------------------

Select several photos or videos in your gallery and share them to Pocketfuse together. Each one becomes
its own job on the PC, run one after another with the faces and options you set; **Results** fills up as
they finish. **Stop the rest** on the Swap tab cancels what has not started.


6. Lab: find the best setting
-----------------------------

The Lab runs one frame of your target through the PC once per value of one setting and shows the tiles
side by side, each with the time it took. Pick *Pixel boost*, *Swap model*, *Swap weight*, *Enhancer*
or *Enhancer blend*, tap **Compare**, then tap the tile you like: it becomes the setting for your swaps.

A swap model or enhancer the PC has not used before is downloaded first (up to 1.6 GB each). The Lab says
so before you start.


7. Live: your camera, swapped
-----------------------------

Add a face on the Swap tab, open **Live** and tap **Go live**. The camera goes to the PC over WebRTC, the
PC swaps every frame on its GPU and streams it back; the badge shows the frames per second.
*Front camera* flips the camera, *Microphone* sends your voice along.

Live needs a direct path between the phone and the PC (the same Wi-Fi, or the same tailnet). If no video
arrives after ten seconds the app says so: a firewall or a guest network usually blocks it.


8. Dub: your voice, their lips
------------------------------

On the Swap tab, **Your voice (lip sync)**: tap **Record**, say a line, tap **Stop**. With *Lip sync*
on, the target's lips follow your recording, with or without a face swap. Use it only on yourself or
people who agreed to it.


9. Puppet: bring a portrait to life
-----------------------------------

Pick a portrait, tap **Start puppet** and look at the front camera. Your head turns, blinks and smiles
are read on the phone (MediaPipe) and only those numbers go to the PC, which redraws the portrait with
FaceFusion's face editor. **Set neutral** recalibrates, *Intensity* scales the movement. Your camera
image never leaves the phone.


10. PC: what your computer is doing
-----------------------------------

GPU load and its history, VRAM, temperature, CPU, memory and free space on the PC's temp drive, live
from FaceFusion's metrics. Below: the session, when its tokens rotate next, and the connection type
(tailnet, LAN). **Benchmark** runs a short swap on the PC and reports frames per second.


11. Troubleshooting
-------------------

| You see | Do this |
|---|---|
| "Could not reach your PC" | Is `serve` still running? Same Wi-Fi or tailnet? On Windows allow Python through the firewall on private networks. |
| "Wrong API key" | The PC restarted with a new key: scan the new QR code, or set `FACEFUSION_API_KEY` so it stays the same. |
| "Getting models ready on your PC" | The first use of a model downloads it. Wait; it happens once. |
| Live connects but stays black | UDP is blocked between phone and PC. Use the same Wi-Fi without client isolation, or Tailscale. |
| A frame comes back blurred | FaceFusion's content check flagged it. Pocketfuse never bypasses it. |
| "The file is too big" | The limit is 512 MB and 4096 px. Trim the video first. |
| Swaps are slow | `serve` runs FaceFusion with `--video-memory-strategy moderate`. Check the PC tab: GPU near 100 % means the GPU is the limit. |


12. Use it responsibly
----------------------

Swap only your own face or the faces of people who agreed to it. Label what you publish as AI
generated. Pocketfuse is licensed OpenRAIL-AS, which forbids harmful, deceptive and non-consensual use.
