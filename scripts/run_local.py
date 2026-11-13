#!/usr/bin/env python3
"""User launcher: optional hidden key entry, no persistent secrets."""
import argparse
import getpass
import json
import os
from pathlib import Path
import sys
import urllib.request
import webbrowser

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--no-browser',action='store_true')
    parser.add_argument('--demo',action='store_true',help='Skip credential prompt')
    parser.add_argument('--port',type=int,default=8787)
    args=parser.parse_args()
    if not 1 <= args.port <= 65535: parser.error('--port must be between 1 and 65535')
    url=f'http://127.0.0.1:{args.port}/'
    from backend.server import make_server, ui_revision
    try: revision=ui_revision(ROOT/'web')
    except OSError:
        print('현재 화면 파일을 확인하지 못했습니다. 프로젝트 파일을 확인한 뒤 다시 실행해 주세요.')
        return 1
    try:
        with urllib.request.urlopen(url+'api/health',timeout=1) as response:health=json.load(response)
        if health.get('service')=='arrival-pet-prototype':
            if health.get('uiRevision')!=revision:
                print('이 포트에는 다른 버전의 마중 화면이 실행 중입니다. 기존 서버는 그대로 유지했습니다.\n'
                      '현재 프로젝트를 새 포트로 실행하세요: python3 scripts/run_local.py --port 8790')
                return 2
            print('이미 실행 중인 마중 시제품을 엽니다. '+url)
            if not args.no_browser:webbrowser.open(url)
            return 0
    except Exception:pass
    if not args.demo and not os.environ.get('DATA_GO_KR_SERVICE_KEY') and sys.stdin.isatty():
        print('마중 · Pleos 시제품\n인증키는 화면이나 파일에 남기지 않고 이번 실행 동안만 사용합니다.')
        key=getpass.getpass('공공데이터 인증키 입력 (가상 공고만 보려면 Enter): ').strip()
        if key:os.environ['DATA_GO_KR_SERVICE_KEY']=key
    try:server=make_server(args.port)
    except OSError:
        print('포트가 이미 사용 중입니다. 기존 서버를 확인하거나 --port 번호를 바꿔 주세요.');return 1
    runtime=ROOT/'.run';runtime.mkdir(exist_ok=True);(runtime/'server.pid').write_text(str(os.getpid())+'\n')
    print('시제품 주소: '+url+'\n이동 시작 → 목적지 도착 → P단 주차 순서로 테스트하세요.\n종료: 이 창에서 Ctrl+C')
    if not args.no_browser:webbrowser.open(url)
    try:server.serve_forever()
    except KeyboardInterrupt:print('\n마중 서버를 종료했습니다.')
    finally:server.server_close()
    return 0

if __name__=='__main__':raise SystemExit(main())
