#!/usr/bin/env python3
"""Sign an aligned CI APK locally; keep signing credentials outside Git."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('unsigned', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--apksigner', required=True, type=Path)
    parser.add_argument('--keystore', required=True, type=Path)
    parser.add_argument('--credentials', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists(): parser.error('Output exists; choose a new filename')
    root = Path(__file__).resolve().parents[1]
    for path in (args.keystore, args.credentials):
        if path.resolve().is_relative_to(root): parser.error('Signing material must remain outside repository')
    secrets = json.loads(args.credentials.read_text())
    env = os.environ.copy()
    env['MAJOONG_STORE_PASSWORD'] = secrets['store_password']
    env['MAJOONG_KEY_PASSWORD'] = secrets['key_password']
    java = str(Path(env['JAVA_HOME'])/'bin/java') if env.get('JAVA_HOME') else 'java'
    base = [java, '-Xmx128m', '-jar', str(args.apksigner)]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(base+['sign','--ks',str(args.keystore),'--ks-key-alias',secrets['alias'],
        '--ks-pass','env:MAJOONG_STORE_PASSWORD','--key-pass','env:MAJOONG_KEY_PASSWORD',
        '--out',str(args.output),str(args.unsigned)], env=env, check=True)
    check = subprocess.run(base+['verify','--verbose','--print-certs',str(args.output)],
        check=True,capture_output=True,text=True)
    for line in check.stdout.splitlines():
        if 'certificate SHA-256 digest:' in line or line.startswith('Verified using'): print(line)
    print('APK SHA-256:', hashlib.sha256(args.output.read_bytes()).hexdigest())

if __name__ == '__main__': main()
