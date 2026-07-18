"""
Wire protocol for the intercom system.

Every UDP packet starts with a fixed header:

    byte 0        : message type (see MsgType below)
    bytes 1-4     : session token (uint32, big-endian). 0 for pre-auth packets.
    bytes 5+      : type-specific payload

This is intentionally simple and stateless-ish: the session token in every
packet is what lets the server figure out which phone sent it without a
persistent TCP connection. If a phone drops off wifi and comes back, it just
resumes sending packets with the same token and everything picks back up
with no reconnect handshake needed.
"""

import struct

HEADER_FMT = "!BI"          # type (1 byte), token (4 bytes unsigned)
HEADER_SIZE = struct.calcsize(HEADER_FMT)


class MsgType:
    AUTH_REQUEST = 0x01     # client -> server: payload = UTF-8 password + "\x00" + UTF-8 device name
    AUTH_RESPONSE = 0x02    # server -> client: payload = 1 byte status (0=fail,1=ok) + 4 byte token if ok
    HEARTBEAT = 0x03        # client -> server: payload = empty
    HEARTBEAT_ACK = 0x04    # server -> client: payload = empty
    AUDIO_FRAME = 0x05      # both directions: payload = 4 byte seq + 8 byte origin timestamp (ms) + PCM audio
    LOGOUT = 0x06           # client -> server: payload = empty
    PING = 0x07             # client -> server: payload = 8 byte timestamp (ms), echoed back immediately
    PONG = 0x08             # server -> client: payload = same 8 byte timestamp, unmodified
    LOOPBACK_ON = 0x09      # client -> server: payload = empty. Server stops excluding this client's own
                            # audio from its mix, for latency self-testing.
    LOOPBACK_OFF = 0x0A     # client -> server: payload = empty. Restores normal self-exclusion.
    MUTE_ON = 0x0B          # server -> client: payload = empty. Admin has force-muted this phone's mic.
    MUTE_OFF = 0x0C         # server -> client: payload = empty. Admin has lifted the forced mute.
    TEXT_MESSAGE = 0x0D     # server -> client: payload = UTF-8 message text, shown/rung on the phone


def pack_header(msg_type: int, token: int) -> bytes:
    return struct.pack(HEADER_FMT, msg_type, token)


def unpack_header(data: bytes):
    if len(data) < HEADER_SIZE:
        return None
    msg_type, token = struct.unpack(HEADER_FMT, data[:HEADER_SIZE])
    return msg_type, token, data[HEADER_SIZE:]


def make_auth_request(password: str, device_name: str = "") -> bytes:
    payload = password.encode("utf-8") + b"\x00" + device_name.encode("utf-8")
    return pack_header(MsgType.AUTH_REQUEST, 0) + payload


def parse_auth_request_payload(payload: bytes):
    """Returns (password, device_name) from an AUTH_REQUEST payload."""
    if b"\x00" in payload:
        password_bytes, name_bytes = payload.split(b"\x00", 1)
    else:
        password_bytes, name_bytes = payload, b""
    try:
        return password_bytes.decode("utf-8"), name_bytes.decode("utf-8")
    except UnicodeDecodeError:
        return "", ""


def make_auth_response(ok: bool, token: int = 0) -> bytes:
    payload = struct.pack("!B", 1 if ok else 0)
    if ok:
        payload += struct.pack("!I", token)
    return pack_header(MsgType.AUTH_RESPONSE, 0) + payload


def make_heartbeat(token: int) -> bytes:
    return pack_header(MsgType.HEARTBEAT, token)


def make_heartbeat_ack(token: int) -> bytes:
    return pack_header(MsgType.HEARTBEAT_ACK, token)


def make_audio_frame(token: int, seq: int, origin_timestamp_ms: int, pcm_bytes: bytes) -> bytes:
    return (
        pack_header(MsgType.AUDIO_FRAME, token)
        + struct.pack("!IQ", seq, origin_timestamp_ms)
        + pcm_bytes
    )


def make_logout(token: int) -> bytes:
    return pack_header(MsgType.LOGOUT, token)


def make_ping(token: int, timestamp_ms: int) -> bytes:
    return pack_header(MsgType.PING, token) + struct.pack("!Q", timestamp_ms)


def make_pong(token: int, timestamp_ms: int) -> bytes:
    return pack_header(MsgType.PONG, token) + struct.pack("!Q", timestamp_ms)


def make_loopback_on(token: int) -> bytes:
    return pack_header(MsgType.LOOPBACK_ON, token)


def make_loopback_off(token: int) -> bytes:
    return pack_header(MsgType.LOOPBACK_OFF, token)


def make_mute_on(token: int) -> bytes:
    return pack_header(MsgType.MUTE_ON, token)


def make_mute_off(token: int) -> bytes:
    return pack_header(MsgType.MUTE_OFF, token)


def make_text_message(token: int, text: str) -> bytes:
    return pack_header(MsgType.TEXT_MESSAGE, token) + text.encode("utf-8")


def parse_audio_frame_payload(payload: bytes):
    """Returns (seq, origin_timestamp_ms, pcm_bytes) from an AUDIO_FRAME payload (post-header)."""
    seq, origin_timestamp_ms = struct.unpack("!IQ", payload[:12])
    return seq, origin_timestamp_ms, payload[12:]


def parse_ping_payload(payload: bytes) -> int:
    return struct.unpack("!Q", payload[:8])[0]
