"""Loopback TLS for the real model used by the Android acceptance suite.

The Android instrumentation process trusts only this run's certificate. The proxy
never receives credentials for external providers or any real user records.
"""

import http.server
import json
import pathlib
import ssl
import sys
import time
import urllib.error
import urllib.request


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != '/v1/chat/completions':
            self.send_error(404)
            return
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        started = time.monotonic()
        try:
            upstream = urllib.request.Request(
                'http://127.0.0.1:9081/v1/chat/completions',
                data=json.dumps(request).encode(),
                headers={'Content-Type': 'application/json'},
            )
            with urllib.request.urlopen(upstream, timeout=180) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read()
        except (OSError, TimeoutError) as error:
            status = 502
            body = json.dumps({'error': str(error)}).encode()
        entry = {
            'input': request['messages'][1]['content'],
            'round': (len(request['messages']) - 2) // 2 + 1,
            'status': status,
            'seconds': round(time.monotonic() - started, 2),
            'response': json.loads(body),
        }
        with pathlib.Path('acceptance/model-responses.jsonl').open('a', encoding='utf-8') as output:
            output.write(json.dumps(entry, ensure_ascii=False) + '\n')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


server = http.server.ThreadingHTTPServer(('127.0.0.1', 9443), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(sys.argv[1], sys.argv[2])
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
