"""
Intercom server configuration.
Edit these values for your environment, then run server.py.
"""

# --- Network ---
LISTEN_HOST = "0.0.0.0"     # listen on all interfaces
LISTEN_PORT = 5005          # UDP port phones connect to. Make sure Windows Firewall allows this.

# --- Auth ---
PASSWORD = "changeme123"    # shared password every phone must enter to log in
SESSION_TIMEOUT_SECONDS = 8 * 60 * 60   # 8 hours, per spec
HEARTBEAT_INTERVAL_SECONDS = 1.0        # how often clients are expected to ping
CLIENT_TIMEOUT_SECONDS = 3.0            # if no packet received in this long, mark client offline

# --- Audio ---
SAMPLE_RATE = 16000          # Hz. 16kHz mono is plenty for intelligible voice, keeps bandwidth tiny.
FRAME_MS = 20                 # audio frame size in milliseconds
SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS // 1000   # 320 samples
BYTES_PER_SAMPLE = 2           # 16-bit PCM
FRAME_BYTES = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE   # 640 bytes per frame

# --- USB audio interface bridge (the external system) ---
# Set to None to disable the USB bridge entirely (phones will still talk to each other).
# Use list_audio_devices.py to find the right device name/index first.
USB_INPUT_DEVICE = None    # e.g. "Line In (USB Audio CODEC)" or a sounddevice index (int)
USB_OUTPUT_DEVICE = None   # e.g. "Line Out (USB Audio CODEC)" or a sounddevice index (int)

# --- Logging ---
LOG_LEVEL = "INFO"   # DEBUG for verbose per-packet logging while troubleshooting
