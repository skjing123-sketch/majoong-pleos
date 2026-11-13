#!/usr/bin/env python3
"""Serve this checkout's UI; proxy API requests to an existing local backend."""
import argparse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
ROOT = Path(__file__).resolve().parents[1] / 'web'

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None

def make_server(port=18789,api_port=8787,web_root=ROOT):
    if not 0 <= port <= 65535 or not 1 <= api_port <= 65535:
        raise ValueError('A valid local port is required')
    opener=urllib.request.build_opener(NoRedirect)
    class Handler(SimpleHTTPRequestHandler):
        def __init__(self,*a,**k): super().__init__(*a,directory=str(web_root),**k)
        def log_message(self,*a): pass
        def end_headers(self):
            self.send_header('Cache-Control','no-store')
            self.send_header('X-Content-Type-Options','nosniff')
            self.send_header('Referrer-Policy','no-referrer')
            super().end_headers()
        def do_GET(self):
            allowed={f'{host}:{self.server.server_port}' for host in ('127.0.0.1','localhost','10.0.2.2')}
            if self.headers.get('Host','') not in allowed:
                self.send_error(403);return
            path=urllib.parse.urlsplit(self.path).path
            public_navigation=(not path.startswith('/api/') and
                self.headers.get('Sec-Fetch-Mode')=='navigate' and
                self.headers.get('Sec-Fetch-Dest')=='document')
            origin=self.headers.get('Origin')
            # Apply the backend's boundary before forwarding; an unlabelled loopback
            # request must never launder a foreign browser origin into a trusted one.
            if not public_navigation and ((origin and origin not in {f'http://{host}' for host in allowed}) or
                    self.headers.get('Sec-Fetch-Site')=='cross-site'):
                self.send_error(403);return
            if not self.path.startswith('/api/'): return super().do_GET()
            try:
                with opener.open(f'http://127.0.0.1:{api_port}{self.path}',timeout=25) as r:
                    body=r.read(9*1024*1024)
                    self.send_response(r.status)
                    self.send_header('Content-Type',r.headers.get('Content-Type','application/json'))
                    self.send_header('Content-Length',str(len(body)))
                    self.end_headers();self.wfile.write(body)
            except urllib.error.HTTPError as e:
                if 300 <= e.code < 400:
                    e.close();self.send_error(502,'Local backend redirect rejected');return
                body=e.read(65536);self.send_response(e.code);e.close()
                self.send_header('Content-Type','application/json; charset=utf-8')
                self.send_header('Content-Length',str(len(body)))
                self.end_headers();self.wfile.write(body)
            except (TimeoutError,OSError): self.send_error(504,'Local backend unavailable')
    return ThreadingHTTPServer(('127.0.0.1',port),Handler)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--port',type=int,default=18789)
    parser.add_argument('--api-port',type=int,default=8787)
    args=parser.parse_args()
    if not 1 <= args.port <= 65535 or not 1 <= args.api_port <= 65535:
        parser.error('Ports must be between 1 and 65535')
    server=make_server(args.port,args.api_port)
    print(f'UI http://127.0.0.1:{args.port}/, existing API port {args.api_port}',flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()

if __name__ == '__main__': main()
