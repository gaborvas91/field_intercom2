"""
Run this once to find the correct device name/index for your USB audio
interface, then put it into config.py as USB_INPUT_DEVICE / USB_OUTPUT_DEVICE.

    python list_audio_devices.py
"""
import sounddevice as sd

print(sd.query_devices())
print()
print("Use either the index number (left column) or the exact name string")
print("in config.py for USB_INPUT_DEVICE / USB_OUTPUT_DEVICE.")
