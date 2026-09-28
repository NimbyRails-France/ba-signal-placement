"""Fetch the pinned Windows SDK kit from NRF, with public GitHub fallback.
No publisher credential is read or embedded in the kit. Verify identical size
and SHA-256 before extraction, whichever mirror supplied the bytes.
"""
import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request
import zipfile

ORIGIN = 'https://releases.nimbyrails-france.fr'
GITHUB = 'https://github.com/NimbyRails-France/sdk/releases/download/'


class OfficialRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if (parsed.scheme != 'https' or parsed.username is not None or parsed.port not in (None, 443) or
                parsed.hostname not in ('github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com')):
            raise ValueError('Unapproved SDK download redirect')
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def open_url(url, timeout=30):
    return urllib.request.build_opener(OfficialRedirects).open(urllib.request.Request(url,
        headers={'User-Agent': 'NRF-CI-SDK', 'Accept': 'application/vnd.github+json'}), timeout=timeout)


def document(url):
    with open_url(url, 10) as response:
        data = response.read(4 * 1024 * 1024 + 1)
    if len(data) > 4 * 1024 * 1024:
        raise ValueError('SDK catalogue too large')
    return json.loads(data.decode('utf-8-sig'))


def released_asset(version):
    tag = 'v' + version
    name = f'NimbyRailsFranceSDK-kotlin-{version}-windows-x64.zip'
    loaders = [
        lambda: document(ORIGIN + '/v1/catalog.json')['projects']['sdk'],
        lambda: document(GITHUB + 'catalogue/releases.json')['releases'],
        lambda: [document('https://api.github.com/repos/NimbyRails-France/sdk/releases/tags/' + tag)],
    ]
    failures = []
    for load in loaders:
        try:
            release = next(r for r in load() if r['tag_name'] == tag and not r['draft'] and r.get('published_at') and r['prerelease'] == ('-' in version))
            asset = next(a for a in release['assets'] if a['name'] == name and a['state'] == 'uploaded')
            urls = [f'{ORIGIN}/releases/sdk/{tag}/{name}', f'{GITHUB}{tag}/{name}']
            if asset['browser_download_url'] not in urls or not 0 < asset['size'] <= 536870912 or not re.fullmatch('sha256:[0-9a-f]{64}', asset.get('digest') or ''):
                raise ValueError('Unverifiable released SDK asset')
            return asset, urls
        except Exception as error:
            failures.append(str(error))
    raise ValueError('Cannot locate the pinned SDK release: ' + '; '.join(failures))


def fetch():
    version = Path('.ci/sdk/VERSION').read_text().strip()
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-(?:alpha|beta)\.[1-9]\d*)?', version):
        raise ValueError('Invalid pinned SDK version')
    asset, urls = released_asset(version)
    archive = Path('.ci/sdk-kit.zip')
    for index, url in enumerate(urls):
        try:
            with open_url(url) as response, archive.open('wb') as output:
                remaining = asset['size']
                while remaining:
                    chunk = response.read(min(1024 * 1024, remaining))
                    if not chunk:
                        raise ValueError('Truncated SDK archive')
                    output.write(chunk)
                    remaining -= len(chunk)
                if response.read(1):
                    raise ValueError('SDK archive exceeds declared size')
            with archive.open('rb') as source:
                if asset['digest'] != 'sha256:' + hashlib.file_digest(source, 'sha256').hexdigest():
                    raise ValueError('SDK archive checksum mismatch')
            break
        except Exception:
            archive.unlink(missing_ok=True)
            if index == len(urls) - 1:
                raise
    target = Path('.ci/sdk-kit').resolve()
    target.mkdir()
    with zipfile.ZipFile(archive) as content:
        entries = content.infolist()
        if len(entries) > 50000 or sum(e.file_size for e in entries) > 2 * 1024 * 1024 * 1024 or content.testzip() is not None:
            raise ValueError('Invalid or oversized SDK archive')
        for entry in entries:
            path = (target / entry.filename).resolve()
            if (not path.is_relative_to(target) or entry.filename.startswith(('/', '\\')) or '\\' in entry.filename or
                    ':' in entry.filename or (entry.external_attr >> 16) & 0o170000 == 0o120000):
                raise ValueError('Unsafe SDK archive entry')
        content.extractall(target)
    metadata = json.loads((target / 'sdk.json').read_text(encoding='utf-8-sig'))
    if metadata['sdkVersion'] != version or metadata['target'] != 'mingw_x64':
        raise ValueError('SDK kit identity mismatch')
    print('Verified released SDK kit:', version, asset['digest'])


if __name__ == '__main__':
    fetch()
