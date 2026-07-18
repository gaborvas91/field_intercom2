"""
Standalone smoke test -- runs the real server logic and two fake "phones"
over real UDP sockets on localhost, to verify:
  - auth accepts correct password, rejects wrong password
  - audio sent by phone A arrives, mixed, at phone B (and NOT echoed back
    to phone A itself)
  - heartbeat gets acked

Run: python test_server.py
"""
import asyncio
import socket
import struct
import sys
import numpy as np

import config
import protocol
from protocol import MsgType
from server import IntercomServer, ServerProtocol

TEST_PORT = 5999


async def run_test():
    config.LISTEN_PORT = TEST_PORT
    server = IntercomServer()
    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(
        lambda: ServerProtocol(server),
        local_addr=(config.LISTEN_HOST, TEST_PORT),
    )
    mixing_task = asyncio.ensure_future(server.mixing_loop())

    def make_socket():
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setblocking(False)
        s.bind(("127.0.0.1", 0))
        return s

    sock_a = make_socket()
    sock_b = make_socket()
    server_addr = ("127.0.0.1", TEST_PORT)

    async def recv(sock, timeout=1.0):
        loop_ = asyncio.get_running_loop()
        fut = loop_.create_future()

        def on_readable():
            if not fut.done():
                fut.set_result(sock.recv(4096))

        loop_.add_reader(sock.fileno(), on_readable)
        try:
            return await asyncio.wait_for(fut, timeout)
        finally:
            loop_.remove_reader(sock.fileno())

    def drain(sock):
        """Discard any packets already sitting in the socket's receive buffer
        from a previous test phase, so the next test doesn't accidentally
        read a stale leftover instead of a fresh one."""
        while True:
            try:
                sock.recv(4096)
            except BlockingIOError:
                break

    # --- Test 1: wrong password rejected ---
    sock_a.sendto(protocol.make_auth_request("wrong-password"), server_addr)
    resp = await recv(sock_a)
    _, _, payload = protocol.unpack_header(resp)
    assert payload[0] == 0, "expected auth failure"
    print("PASS: wrong password rejected")

    # --- Test 2: correct password accepted, tokens issued ---
    sock_a.sendto(protocol.make_auth_request(config.PASSWORD), server_addr)
    resp = await recv(sock_a)
    _, _, payload = protocol.unpack_header(resp)
    assert payload[0] == 1, "expected auth success"
    token_a = struct.unpack("!I", payload[1:5])[0]
    print("PASS: phone A authenticated, token", token_a)

    sock_b.sendto(protocol.make_auth_request(config.PASSWORD), server_addr)
    resp = await recv(sock_b)
    _, _, payload = protocol.unpack_header(resp)
    token_b = struct.unpack("!I", payload[1:5])[0]
    print("PASS: phone B authenticated, token", token_b)

    # --- Test 3: heartbeat ack ---
    sock_a.sendto(protocol.make_heartbeat(token_a), server_addr)
    resp = await recv(sock_a)
    msg_type, _, _ = protocol.unpack_header(resp)
    assert msg_type == MsgType.HEARTBEAT_ACK
    print("PASS: heartbeat acked")

    # --- Test 4: phone A talks, phone B hears it, phone A hears silence (no self-echo) ---
    tone = (np.sin(np.linspace(0, 6.28, config.SAMPLES_PER_FRAME)) * 10000).astype(np.int16)
    for _ in range(3):  # send a few frames to make sure one lands in a mixing tick
        sock_a.sendto(protocol.make_audio_frame(token_a, 1, 12345, tone.tobytes()), server_addr)
        await asyncio.sleep(config.FRAME_MS / 1000.0)

    heard_something_on_b = False
    for _ in range(5):
        try:
            resp = await recv(sock_b, timeout=0.1)
        except asyncio.TimeoutError:
            continue
        msg_type, _, payload = protocol.unpack_header(resp)
        if msg_type == MsgType.AUDIO_FRAME:
            _, _, pcm = protocol.parse_audio_frame_payload(payload)
            audio = np.frombuffer(pcm, dtype=np.int16)
            if np.abs(audio).max() > 100:
                heard_something_on_b = True
                break
    assert heard_something_on_b, "phone B should have heard phone A's audio"
    print("PASS: phone B received phone A's mixed audio")

    # --- Test 5: PING is answered immediately with the same timestamp ---
    ts = 123456789
    sock_a.sendto(protocol.make_ping(token_a, ts), server_addr)
    echoed_ts = None
    for _ in range(10):
        try:
            resp = await recv(sock_a, timeout=0.3)
        except asyncio.TimeoutError:
            break
        msg_type, _, payload = protocol.unpack_header(resp)
        if msg_type == MsgType.PONG:
            echoed_ts = protocol.parse_ping_payload(payload)
            break
        # ignore stray leftover AUDIO_FRAME packets from the previous test
    assert echoed_ts == ts, "PONG should echo back the exact timestamp sent"
    print("PASS: ping/pong echoes timestamp correctly")

    # --- Test 6: loopback test makes phone A hear its own audio, with timestamp passthrough ---
    sock_a.sendto(protocol.make_loopback_on(token_a), server_addr)
    await asyncio.sleep(0.05)

    origin_ts = 555555
    for _ in range(3):
        sock_a.sendto(protocol.make_audio_frame(token_a, 2, origin_ts, tone.tobytes()), server_addr)
        await asyncio.sleep(config.FRAME_MS / 1000.0)

    heard_self = False
    got_matching_timestamp = False
    for _ in range(6):
        try:
            resp = await recv(sock_a, timeout=0.1)
        except asyncio.TimeoutError:
            continue
        msg_type, _, payload = protocol.unpack_header(resp)
        if msg_type == MsgType.AUDIO_FRAME:
            _, echoed_ts2, pcm = protocol.parse_audio_frame_payload(payload)
            audio = np.frombuffer(pcm, dtype=np.int16)
            if np.abs(audio).max() > 100:
                heard_self = True
            if echoed_ts2 == origin_ts:
                got_matching_timestamp = True
            if heard_self and got_matching_timestamp:
                break
    assert heard_self, "phone A should hear its own audio once loopback test is on"
    assert got_matching_timestamp, "server should pass through the original timestamp during loopback test"
    print("PASS: loopback test lets phone A hear itself with correct timestamp passthrough")

    sock_a.sendto(protocol.make_loopback_off(token_a), server_addr)

    # --- Test 7: admin mute stops audio from reaching other phones, and the
    # muted phone receives a MUTE_ON notice ---
    await asyncio.sleep(0.1)
    drain(sock_a)
    drain(sock_b)

    server.mute_client(token_a)
    got_mute_on = False
    for _ in range(10):
        try:
            resp = await recv(sock_a, timeout=0.3)
        except asyncio.TimeoutError:
            break
        msg_type, _, _ = protocol.unpack_header(resp)
        if msg_type == MsgType.MUTE_ON:
            got_mute_on = True
            break
    assert got_mute_on, "muted phone should receive a MUTE_ON notice"
    print("PASS: muted phone receives MUTE_ON notice")

    drain(sock_b)
    for _ in range(3):
        sock_a.sendto(protocol.make_audio_frame(token_a, 3, 999, tone.tobytes()), server_addr)
        await asyncio.sleep(config.FRAME_MS / 1000.0)

    heard_muted_audio_on_b = False
    for _ in range(5):
        try:
            resp = await recv(sock_b, timeout=0.1)
        except asyncio.TimeoutError:
            continue
        msg_type, _, payload = protocol.unpack_header(resp)
        if msg_type == MsgType.AUDIO_FRAME:
            _, _, pcm = protocol.parse_audio_frame_payload(payload)
            audio = np.frombuffer(pcm, dtype=np.int16)
            if np.abs(audio).max() > 100:
                heard_muted_audio_on_b = True
    assert not heard_muted_audio_on_b, "phone B should NOT hear a muted phone's audio"
    print("PASS: muted phone's audio does not reach other phones")

    server.unmute_client(token_a)
    got_mute_off = False
    for _ in range(10):
        try:
            resp = await recv(sock_a, timeout=0.3)
        except asyncio.TimeoutError:
            break
        msg_type, _, _ = protocol.unpack_header(resp)
        if msg_type == MsgType.MUTE_OFF:
            got_mute_off = True
            break
    assert got_mute_off, "unmuted phone should receive a MUTE_OFF notice"
    print("PASS: unmuted phone receives MUTE_OFF notice")

    # --- Test 8: admin text message reaches the targeted phone, and broadcast reaches all ---
    server.send_text_message(token_b, "Hello phone B")
    got_message = False
    for _ in range(10):
        try:
            resp = await recv(sock_b, timeout=0.3)
        except asyncio.TimeoutError:
            break
        msg_type, _, payload = protocol.unpack_header(resp)
        if msg_type == MsgType.TEXT_MESSAGE:
            assert payload.decode("utf-8") == "Hello phone B"
            got_message = True
            break
    assert got_message, "targeted text message should be delivered"
    print("PASS: targeted text message delivered correctly")

    server.send_text_message(None, "Broadcast to everyone")
    got_a = got_b = False
    for _ in range(6):
        for sock, name in [(sock_a, "a"), (sock_b, "b")]:
            try:
                resp = await recv(sock, timeout=0.1)
            except asyncio.TimeoutError:
                continue
            msg_type, _, payload = protocol.unpack_header(resp)
            if msg_type == MsgType.TEXT_MESSAGE and payload.decode("utf-8") == "Broadcast to everyone":
                if name == "a":
                    got_a = True
                else:
                    got_b = True
    assert got_a and got_b, "broadcast message should reach every connected phone"
    print("PASS: broadcast message delivered to all phones")

    transport.close()
    mixing_task.cancel()
    print("\nAll tests passed.")


if __name__ == "__main__":
    asyncio.run(run_test())
