"""Publish only the Gradle archive whose tests and Wine lifecycle check passed."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile

plan = json.loads(Path('.release-plan.json').read_text())
manifest = json.loads(Path('build/gradle/distributions/project-windows-x64.json').read_text())
assert manifest['version'] == plan['version'] and manifest['platform'] == 'windows-x64'
assert manifest['channel'] == plan['channel']
repository = os.environ['CI_REPO'].split('/')[-1]
assert repository in ('signalisationfrancaiserealiste', 'signal-placement') and manifest['id'] == repository
source = Path('build/gradle/distributions')
archive = source / manifest['url'].rsplit('/', 1)[-1]
def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()
assert digest(archive) == manifest['sha256'] and archive.stat().st_size == manifest['size']
with zipfile.ZipFile(archive) as content:
    assert content.testzip() is None
out = Path('dist/release')
out.mkdir(parents=True, exist_ok=True)
assert not any(out.iterdir()), 'Release output must be empty'
for name in [archive.name, 'project.json', 'project-windows-x64.json']:
    shutil.copy2(source / name, out / name)
# These are the actual downloadable descriptors, including compatibility
# copies on GitHub. The archive itself is unchanged after native verification.
manifest['url'] = f"https://releases.nimbyrails-france.fr/releases/{repository}/v{plan['version']}/{archive.name}"
for name in ('project.json', 'project-windows-x64.json'):
    (out / name).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(out / 'SHA256SUMS.txt').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in sorted(out.iterdir())))
plan['assets'] = [dict(name=p.name, size=p.stat().st_size, sha256=digest(p)) for p in sorted(out.iterdir())]
Path('.release-plan.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n', encoding='utf-8')
print('Validated Windows mod release assets:', repository)
