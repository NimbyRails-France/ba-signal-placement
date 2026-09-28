"""Publish only the Gradle archive whose tests and Wine lifecycle check passed."""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

plan = json.loads(Path('.release-plan.json').read_text())
manifest = json.loads(Path('build/gradle/distributions/project-windows-x64.json').read_text())
assert manifest['version'] == plan['version'] and manifest['platform'] == 'windows-x64'
assert manifest['channel'] == plan['channel']
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
(out / 'SHA256SUMS.txt').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in sorted(out.iterdir())))
plan['assets'] = [dict(name=p.name, size=p.stat().st_size, sha256=digest(p)) for p in sorted(out.iterdir())]
Path('.release-plan.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n', encoding='utf-8')
print('Validated SFR Windows release assets')
