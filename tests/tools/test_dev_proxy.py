"""Real HTTP regressions on temporary loopback ports; no existing service or key."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
from pathlib import Path
import tempfile
import threading
import unittest

from scripts.dev_proxy import make_server


class ProxyOriginTests(unittest.TestCase):
    def setUp(self):
        self.upstream_requests=[]
        requests=self.upstream_requests
        class Upstream(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_GET(self):
                requests.append(self.path)
                if self.path=='/api/redirect':
                    self.send_response(302)
                    self.send_header('Location',f'http://127.0.0.1:{self.server.server_port}/api/redirect-target')
                    self.end_headers();return
                body=b'{"synthetic":true}'
                self.send_response(200);self.send_header('Content-Type','application/json')
                self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        self.upstream=ThreadingHTTPServer(('127.0.0.1',0),Upstream)
        self.temp=tempfile.TemporaryDirectory()
        Path(self.temp.name,'index.html').write_text('<p>synthetic preview</p>')
        self.proxy=make_server(0,self.upstream.server_port,self.temp.name)
        self.threads=[]
        for server in (self.upstream,self.proxy):
            thread=threading.Thread(target=server.serve_forever,daemon=True)
            thread.start();self.threads.append(thread)

    def tearDown(self):
        for server in (self.proxy,self.upstream): server.shutdown();server.server_close()
        for thread in self.threads: thread.join(timeout=2)
        self.temp.cleanup()

    def request(self,path='/api/health',headers=None):
        connection=http.client.HTTPConnection('127.0.0.1',self.proxy.server_port,timeout=2)
        try:
            connection.request('GET',path,headers=headers or {})
            response=connection.getresponse();response.read()
            return response.status,dict(response.getheaders())
        finally: connection.close()

    def test_foreign_origin_or_cross_site_api_is_rejected_before_upstream(self):
        for headers in ({'Origin':'https://audit.invalid'}, {'Sec-Fetch-Site':'cross-site'},
                        {'Origin':'null'}, {'Origin':f'http://127.0.0.1:{self.proxy.server_port+1}'},
                        {'Host':f'attacker.invalid:{self.proxy.server_port}'}):
            with self.subTest(headers=headers): self.assertEqual(self.request(headers=headers)[0],403)
        self.assertEqual(self.upstream_requests,[])

    def test_same_origin_and_normal_loopback_api_still_work(self):
        self.assertEqual(self.request()[0],200)
        code,headers=self.request(headers={'Origin':f'http://127.0.0.1:{self.proxy.server_port}', 'Sec-Fetch-Site':'same-origin'})
        self.assertEqual(code,200)
        self.assertEqual(headers['Referrer-Policy'],'no-referrer')
        self.assertEqual(self.upstream_requests,['/api/health','/api/health'])

    def test_only_static_document_navigation_gets_cross_site_exception(self):
        headers={'Origin':'https://audit.invalid','Sec-Fetch-Site':'cross-site',
                 'Sec-Fetch-Mode':'navigate','Sec-Fetch-Dest':'document'}
        self.assertEqual(self.request('/',headers)[0],200)
        self.assertEqual(self.request('/api/health',headers)[0],403)
        self.assertEqual(self.upstream_requests,[])

    def test_upstream_redirect_is_not_followed(self):
        self.assertEqual(self.request('/api/redirect')[0],502)
        self.assertEqual(self.upstream_requests,['/api/redirect'])

    def test_invalid_upstream_port_is_rejected(self):
        for port in (-1,0,65536):
            with self.assertRaises(ValueError): make_server(0,port)


if __name__=='__main__': unittest.main()
