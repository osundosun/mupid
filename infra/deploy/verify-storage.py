#!/usr/bin/env python3
"""Create the configured bucket if absent; verify a unique object's round trip.

Run as an administrator on the application server. Uses rendered Compose values,
never prints credentials, and deletes only the uniquely named probe object.
"""
import datetime
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid


def validate_endpoint(value):
    # This tool probes the locally managed SeaweedFS only. Never report success
    # for a different endpoint while silently testing the local service.
    endpoint = urllib.parse.urlsplit(value)
    if (endpoint.scheme != 'http' or endpoint.hostname != 'storage'
            or endpoint.port != 8333 or endpoint.username or endpoint.password
            or endpoint.path not in ('', '/') or endpoint.query or endpoint.fragment):
        raise ValueError('Expected the managed endpoint http://storage:8333')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--read-only', action='store_true', help='Only check access to the existing bucket')
    args = parser.parse_args()
    root = Path('/srv/mupid')
    result = subprocess.run(
        ['docker', 'compose', '--env-file', str(root / '.env'), '-f',
         str(root / 'config/compose.yml'), 'config', '--format', 'json'],
        env=dict(os.environ, MUPID_API_IMAGE=(root / 'current-image').read_text().strip()),
        capture_output=True, text=True, check=True,
    )
    values = json.loads(result.stdout)['services']['api']['environment']
    try:
        validate_endpoint(values.get('MUPID_S3_ENDPOINT', ''))
    except ValueError:
        raise SystemExit('ENDPOINT_MISMATCH: this tool only supports the managed local SeaweedFS')
    result = subprocess.run(['docker', 'inspect', 'mupid-storage'],
                            capture_output=True, text=True, check=True)
    address = json.loads(result.stdout)[0]['NetworkSettings']['Networks']['mupid-backend']['IPAddress']
    bucket = values['MUPID_S3_BUCKET']
    access = values['MUPID_S3_ACCESS_KEY']
    secret = values['MUPID_S3_SECRET']

    def request(method, path, body=b''):
        now = datetime.datetime.now(datetime.timezone.utc)
        stamp, day = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
        digest = hashlib.sha256(body).hexdigest()
        host = 'storage:8333'
        headers = f'host:{host}\nx-amz-content-sha256:{digest}\nx-amz-date:{stamp}\n'
        names = 'host;x-amz-content-sha256;x-amz-date'
        canonical = '\n'.join([method, path, '', headers, names, digest])
        scope = f'{day}/SeaweedFS/s3/aws4_request'
        value = '\n'.join(['AWS4-HMAC-SHA256', stamp, scope, hashlib.sha256(canonical.encode()).hexdigest()])
        key = ('AWS4' + secret).encode()
        for part in [day, 'SeaweedFS', 's3', 'aws4_request']:
            key = hmac.new(key, part.encode(), hashlib.sha256).digest()
        signature = hmac.new(key, value.encode(), hashlib.sha256).hexdigest()
        req = urllib.request.Request(f'http://{address}:8333{path}', data=body, method=method,
            headers={'Host': host, 'x-amz-date': stamp, 'x-amz-content-sha256': digest,
                     'Authorization': f'AWS4-HMAC-SHA256 Credential={access}/{scope}, SignedHeaders={names}, Signature={signature}'})
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, b''

    path = '/' + urllib.parse.quote(bucket, safe='')
    code, _ = request('HEAD', path)
    if code == 404 and not args.read_only:
        code, _ = request('PUT', path)
    if code not in (200, 204):
        raise SystemExit(f'BUCKET_CHECK_FAILED HTTP {code}')
    print('BUCKET_READY')
    if args.read_only:
        return
    probe = path + '/infra-probe/' + uuid.uuid4().hex
    body = 'MUPID storage verification 한글\n'.encode()
    code, _ = request('PUT', probe, body)
    if code != 200:
        raise SystemExit(f'UPLOAD_FAILED HTTP {code}')
    try:
        code, output = request('GET', probe)
        if code != 200 or output != body:
            raise SystemExit(f'ROUNDTRIP_FAILED HTTP {code}')
        print('UPLOAD_DOWNLOAD_MATCH')
    finally:
        code, _ = request('DELETE', probe)
        if code not in (200, 204):
            raise SystemExit(f'PROBE_CLEANUP_FAILED HTTP {code}')
    print('PROBE_DELETED')


if __name__ == '__main__':
    main()
