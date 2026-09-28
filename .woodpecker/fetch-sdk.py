"""Consume an immutable released Windows SDK kit; never rebuild the SDK in a mod job."""
import hashlib
import json
import pathlib
import shutil
import urllib.request
import zipfile

origin = 'https://releases.nimbyrails-france.fr'
version = pathlib.Path('.ci/sdk/VERSION').read_text().strip()
name = f'NimbyRailsFranceSDK-kotlin-{version}-windows-x64.zip'
url = f'{origin}/releases/sdk/v{version}/{name}'
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None
opener = urllib.request.build_opener(NoRedirect)
with opener.open(origin + '/v1/catalog.json', timeout=30) as response:
    catalogue = json.load(response)
assert catalogue['schema'] == 1
release = next(r for r in catalogue['projects']['sdk'] if r['tag_name'] == 'v' + version and not r['draft'])
asset = next(a for a in release['assets'] if a['name'] == name and a['state'] == 'uploaded')
assert asset['browser_download_url'] == url and 0 < asset['size'] < 1024 * 1024 * 1024
archive = pathlib.Path('.ci/sdk-kit.zip')
with opener.open(url, timeout=180) as response, archive.open('wb') as output:
    remaining = asset['size']
    while remaining:
        chunk = response.read(min(1024 * 1024, remaining))
        if not chunk:
            raise ValueError('Truncated SDK archive')
        output.write(chunk)
        remaining -= len(chunk)
    assert not response.read(1), 'SDK archive exceeds declared size'
with archive.open('rb') as source:
    assert asset['digest'] == 'sha256:' + hashlib.file_digest(source, 'sha256').hexdigest()
target = pathlib.Path('.ci/sdk-kit').resolve()
target.mkdir()
with zipfile.ZipFile(archive) as content:
    assert content.testzip() is None
    for entry in content.infolist():
        path = (target / entry.filename).resolve()
        assert path.is_relative_to(target) and not entry.filename.startswith(('/', '\\')) and '\\' not in entry.filename
        assert (entry.external_attr >> 16) & 0o170000 != 0o120000, 'SDK symlink rejected'
    content.extractall(target)
metadata = json.loads((target / 'sdk.json').read_text(encoding='utf-8-sig'))
assert metadata['sdkVersion'] == version and metadata['target'] == 'mingw_x64'
print('Verified released SDK kit:', version, asset['digest'])
