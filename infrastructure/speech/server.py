"""Private, local-only speech worker. No audio/text logging or disk uploads."""
import io
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from faster_whisper import WhisperModel
from faster_whisper.audio import decode_audio

MODEL = os.environ.get("MANGO_SPEECH_MODEL", "base")
model = WhisperModel(MODEL, device="cpu", compute_type="int8", cpu_threads=4, download_root="/models")
slot = threading.BoundedSemaphore(1)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, body):
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self.reply(200 if self.path == "/health" else 404, {"ready": True})

    def do_POST(self):
        if self.path != "/transcribe":
            return self.reply(404, {})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(400, {})
        if not 0 < length <= 20 * 1024 * 1024:
            return self.reply(413, {})
        if not slot.acquire(blocking=False):
            self.close_connection = True
            return self.reply(429, {})
        try:
            self.connection.settimeout(30)
            audio = decode_audio(io.BytesIO(self.rfile.read(length)))
            if len(audio) > 300 * 16000:
                return self.reply(413, {"error": "duration"})
            segments, info = model.transcribe(audio, beam_size=3, vad_filter=True, condition_on_previous_text=False)
            text = " ".join(s.text.strip() for s in segments).strip()
            self.reply(200, {"text": text[:20000], "language": info.language, "model": MODEL})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except ValueError:
            self.reply(422, {"error": "audio"})
        except Exception as error:
            print("Transcription failed:", type(error).__name__, flush=True)
            self.reply(503, {"error": "worker"})
        finally:
            slot.release()


ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
