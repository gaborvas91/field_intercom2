"""
Intercom server.

Run with:  python server.py
Requires:  pip install -r requirements.txt

This process:
  1. Listens for UDP packets from phones (auth, heartbeat, audio).
  2. Every 20ms, builds a personalized "everyone except you" audio mix for
     each connected phone and sends it out.
  3. Optionally bridges audio to/from a USB audio interface, treated as one
     more participant that every phone hears and that hears every phone.

Designed to favor low latency over robustness: if a packet is late or lost,
we mix in silence for that tick rather than waiting for it.
"""

import asyncio
import logging
import secrets
import struct
import time
from collections import deque

import numpy as np

import config
import protocol
from protocol import MsgType

try:
    import sounddevice as sd
    SOUNDDEVICE_AVAILABLE = True
except Exception:
    SOUNDDEVICE_AVAILABLE = False

logging.basicConfig(
    level=getattr(logging, config.LOG_LEVEL),
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger("intercom")

# The admin GUI polls this instead of parsing stdout -- any log line anywhere
# in this process shows up here too, bounded so it can't grow forever.
log_buffer: deque = deque(maxlen=500)


class _BufferHandler(logging.Handler):
    def emit(self, record):
        log_buffer.append(self.format(record))


_buffer_handler = _BufferHandler()
_buffer_handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s"))
log.addHandler(_buffer_handler)

SILENT_FRAME = np.zeros(config.SAMPLES_PER_FRAME, dtype=np.int16)


class ClientState:
    def __init__(self, token: int, addr, device_name: str = ""):
        self.token = token
        self.addr = addr
        self.device_name = device_name or f"Phone {token % 10000}"
        self.session_start = time.monotonic()
        self.last_seen = time.monotonic()
        # Small buffer of incoming (pcm, origin_timestamp_ms) tuples awaiting
        # mixing. Bounded so a burst of packets can't build up
        # latency-inducing backlog.
        self.incoming_frames = deque(maxlen=3)
        # When True, this client's own audio is NOT excluded from its personal
        # mix, and the mix's timestamp field passes through their own audio's
        # origin timestamp -- used only for the client-side latency self-test.
        self.loopback_test = False
        # Admin-facing state, for the dashboard.
        self.muted = False          # forced mute by the admin
        self.is_talking = False     # updated every mixing tick

    def session_expired(self) -> bool:
        return (time.monotonic() - self.session_start) > config.SESSION_TIMEOUT_SECONDS

    def is_connected(self) -> bool:
        return (time.monotonic() - self.last_seen) <= config.CLIENT_TIMEOUT_SECONDS


class IntercomServer:
    def __init__(self):
        self.clients: dict[int, ClientState] = {}
        self.transport = None
        self.usb_in_queue: deque = deque(maxlen=3)
        self.usb_out_queue: deque = deque(maxlen=3)
        self.usb_stream_in = None
        self.usb_stream_out = None
        # Set once the asyncio loop is running (see main()) so that other
        # threads -- e.g. a Tkinter admin GUI -- can safely schedule actions
        # like "mute this client" onto the network thread.
        self.event_loop: asyncio.AbstractEventLoop | None = None

    # ---------------- UDP handling ----------------

    def handle_packet(self, data: bytes, addr):
        parsed = protocol.unpack_header(data)
        if parsed is None:
            return
        msg_type, token, payload = parsed

        if msg_type == MsgType.AUTH_REQUEST:
            self._handle_auth(payload, addr)
            return

        client = self.clients.get(token)
        if client is None:
            # Unknown/expired session -- silently ignore. The phone's own
            # heartbeat timeout logic will show it as disconnected and it
            # will need to log in again.
            return

        client.last_seen = time.monotonic()
        client.addr = addr  # phone's IP/port can change if it roams APs

        if msg_type == MsgType.HEARTBEAT:
            self.transport.sendto(protocol.make_heartbeat_ack(token), addr)
        elif msg_type == MsgType.AUDIO_FRAME:
            seq, origin_ts, pcm_bytes = protocol.parse_audio_frame_payload(payload)
            if not client.muted:
                client.incoming_frames.append((pcm_bytes, origin_ts))
            # If muted, we silently drop it -- this enforces the mute even if
            # a client somehow kept transmitting, not just when it behaves.
        elif msg_type == MsgType.PING:
            # Answered immediately, bypassing the mixing tick entirely, so
            # this measures raw network + server overhead only.
            timestamp_ms = protocol.parse_ping_payload(payload)
            self.transport.sendto(protocol.make_pong(token, timestamp_ms), addr)
        elif msg_type == MsgType.LOOPBACK_ON:
            client.loopback_test = True
            log.info("Loopback latency test started for client %s", token)
        elif msg_type == MsgType.LOOPBACK_OFF:
            client.loopback_test = False
            log.info("Loopback latency test stopped for client %s", token)
        elif msg_type == MsgType.LOGOUT:
            log.info("%s logged out", client.device_name)
            del self.clients[token]

    def _handle_auth(self, payload: bytes, addr):
        password, device_name = protocol.parse_auth_request_payload(payload)

        if password != config.PASSWORD:
            log.warning("Failed login attempt from %s", addr)
            self.transport.sendto(protocol.make_auth_response(False), addr)
            return

        token = secrets.randbits(32)
        self.clients[token] = ClientState(token, addr, device_name)
        log.info("%s connected from %s", self.clients[token].device_name, addr)
        self.transport.sendto(protocol.make_auth_response(True, token), addr)

    # ---------------- Admin actions (called from the admin GUI thread via
    # event_loop.call_soon_threadsafe -- see admin_gui.py) ----------------

    def mute_client(self, token: int):
        client = self.clients.get(token)
        if client is None:
            return
        client.muted = True
        client.incoming_frames.clear()
        log.info("%s muted by admin", client.device_name)
        if self.transport:
            self.transport.sendto(protocol.make_mute_on(token), client.addr)

    def unmute_client(self, token: int):
        client = self.clients.get(token)
        if client is None:
            return
        client.muted = False
        log.info("%s unmuted by admin", client.device_name)
        if self.transport:
            self.transport.sendto(protocol.make_mute_off(token), client.addr)

    def send_text_message(self, token: int | None, text: str):
        """token=None broadcasts to every connected client."""
        if not self.transport:
            return
        targets = [self.clients[token]] if token is not None and token in self.clients \
            else list(self.clients.values())
        for client in targets:
            self.transport.sendto(protocol.make_text_message(client.token, text), client.addr)
        if token is None:
            log.info("Broadcast message sent to %d client(s): %s", len(targets), text)
        elif targets:
            log.info("Message sent to %s: %s", targets[0].device_name, text)

    # ---------------- Mixing loop ----------------

    async def mixing_loop(self):
        tick_seconds = config.FRAME_MS / 1000.0
        next_tick = time.monotonic()
        while True:
            next_tick += tick_seconds
            self._mix_and_send()
            self._prune_expired_sessions()
            sleep_for = next_tick - time.monotonic()
            if sleep_for > 0:
                await asyncio.sleep(sleep_for)
            else:
                # We're behind schedule; resync instead of spiraling.
                next_tick = time.monotonic()

    def _mix_and_send(self):
        # Gather this tick's audio from every connected client + the USB bridge.
        # talker_audio[token] = (pcm_as_int16_array, origin_timestamp_ms)
        talker_audio: dict[int, tuple] = {}
        for token, client in self.clients.items():
            if not client.is_connected():
                continue
            if client.incoming_frames:
                pcm_bytes, origin_ts = client.incoming_frames.popleft()
                talker_audio[token] = (np.frombuffer(pcm_bytes, dtype=np.int16), origin_ts)

        usb_audio = None
        if self.usb_in_queue:
            usb_audio = self.usb_in_queue.popleft()

        if not talker_audio and usb_audio is None:
            return  # total silence this tick, nothing to send

        # Sum of everyone (int32 headroom to avoid overflow before clipping).
        total_sum = np.zeros(config.SAMPLES_PER_FRAME, dtype=np.int32)
        for audio, _ts in talker_audio.values():
            total_sum += audio
        phones_only_sum = total_sum.copy()
        if usb_audio is not None:
            total_sum += usb_audio

        # Personalized mix per phone: everyone else, minus their own voice --
        # unless they've started a loopback latency test, in which case we
        # deliberately leave their own voice in so they hear (and can time)
        # the full round trip.
        for token, client in self.clients.items():
            client.is_talking = token in talker_audio
            if not client.is_connected():
                continue
            mix = total_sum.copy()
            own_timestamp = 0
            if token in talker_audio:
                own_audio, own_timestamp = talker_audio[token]
                if not client.loopback_test:
                    mix -= own_audio
            clipped = np.clip(mix, -32768, 32767).astype(np.int16)
            packet = protocol.make_audio_frame(token, 0, own_timestamp, clipped.tobytes())
            self.transport.sendto(packet, client.addr)

        # USB output hears every phone (no exclusion needed, it's an
        # external, non-looping endpoint).
        if self.usb_stream_out is not None:
            clipped = np.clip(phones_only_sum, -32768, 32767).astype(np.int16)
            self.usb_out_queue.append(clipped)

    def _prune_expired_sessions(self):
        expired = [t for t, c in self.clients.items() if c.session_expired()]
        for token in expired:
            log.info("Session %s expired after %ss, logging out", token, config.SESSION_TIMEOUT_SECONDS)
            del self.clients[token]

    # ---------------- USB audio interface bridge ----------------

    def start_usb_bridge(self):
        if not SOUNDDEVICE_AVAILABLE:
            log.warning("sounddevice not installed -- USB bridge disabled")
            return
        if config.USB_INPUT_DEVICE is None and config.USB_OUTPUT_DEVICE is None:
            log.info("USB bridge not configured (see config.py) -- skipping")
            return

        if config.USB_INPUT_DEVICE is not None:
            def in_callback(indata, frames, time_info, status):
                if status:
                    log.debug("USB input status: %s", status)
                mono = indata[:, 0] if indata.ndim > 1 else indata
                self.usb_in_queue.append((mono * 32767).astype(np.int16))

            self.usb_stream_in = sd.InputStream(
                device=config.USB_INPUT_DEVICE,
                samplerate=config.SAMPLE_RATE,
                channels=1,
                dtype="float32",
                blocksize=config.SAMPLES_PER_FRAME,
                callback=in_callback,
            )
            self.usb_stream_in.start()
            log.info("USB input stream started on device %s", config.USB_INPUT_DEVICE)

        if config.USB_OUTPUT_DEVICE is not None:
            def out_callback(outdata, frames, time_info, status):
                if status:
                    log.debug("USB output status: %s", status)
                if self.usb_out_queue:
                    frame = self.usb_out_queue.popleft().astype(np.float32) / 32768.0
                else:
                    frame = np.zeros(frames, dtype=np.float32)
                outdata[:, 0] = frame

            self.usb_stream_out = sd.OutputStream(
                device=config.USB_OUTPUT_DEVICE,
                samplerate=config.SAMPLE_RATE,
                channels=1,
                dtype="float32",
                blocksize=config.SAMPLES_PER_FRAME,
                callback=out_callback,
            )
            self.usb_stream_out.start()
            log.info("USB output stream started on device %s", config.USB_OUTPUT_DEVICE)


class ServerProtocol(asyncio.DatagramProtocol):
    def __init__(self, server: IntercomServer):
        self.server = server

    def connection_made(self, transport):
        self.server.transport = transport

    def datagram_received(self, data, addr):
        try:
            self.server.handle_packet(data, addr)
        except Exception:
            log.exception("Error handling packet from %s", addr)


async def main():
    server = IntercomServer()
    server.start_usb_bridge()

    loop = asyncio.get_running_loop()
    server.event_loop = loop
    transport, _ = await loop.create_datagram_endpoint(
        lambda: ServerProtocol(server),
        local_addr=(config.LISTEN_HOST, config.LISTEN_PORT),
    )
    log.info("Intercom server listening on %s:%s", config.LISTEN_HOST, config.LISTEN_PORT)
    log.info("Sample rate=%sHz frame=%sms (%s bytes/frame)",
              config.SAMPLE_RATE, config.FRAME_MS, config.FRAME_BYTES)

    try:
        await server.mixing_loop()
    finally:
        transport.close()


def run_server_in_background_thread() -> IntercomServer:
    """Starts the whole server (asyncio event loop + mixing loop) on a
    dedicated background thread and returns the IntercomServer instance
    immediately, before it's necessarily finished starting up. Used by
    admin_gui.py so Tkinter can own the main thread."""
    import threading

    server = IntercomServer()

    def runner():
        asyncio.run(_serve_forever(server))

    thread = threading.Thread(target=runner, daemon=True)
    thread.start()
    return server


async def _serve_forever(server: IntercomServer):
    server.start_usb_bridge()
    loop = asyncio.get_running_loop()
    server.event_loop = loop
    transport, _ = await loop.create_datagram_endpoint(
        lambda: ServerProtocol(server),
        local_addr=(config.LISTEN_HOST, config.LISTEN_PORT),
    )
    log.info("Intercom server listening on %s:%s", config.LISTEN_HOST, config.LISTEN_PORT)
    try:
        await server.mixing_loop()
    finally:
        transport.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log.info("Shutting down")
