"""Expose existing Gradle artifact cache as a local Maven repository (no downloads)."""
import os
import shutil
from pathlib import Path
import xml.sax.saxutils as xml
source = Path(os.environ['USERPROFILE']) / '.gradle/caches/modules-2/files-2.1'
dest = Path(__file__).resolve().parents[1] / '.local/maven'
count = 0
for group in source.iterdir():
    if not group.is_dir(): continue
    for artifact in group.iterdir():
        if not artifact.is_dir(): continue
        versions = []
        for version in artifact.iterdir():
            if not version.is_dir(): continue
            versions.append(version.name)
            target = dest / group.name.replace('.', '/') / artifact.name / version.name
            target.mkdir(parents=True, exist_ok=True)
            for file in version.glob('*/*'):
                if file.suffix not in ('.pom', '.jar', '.aar', '.module'): continue
                output = target / file.name
                if not output.exists():
                    try: os.link(file, output)
                    except OSError: shutil.copy2(file, output)
                    count += 1
        if versions:
            (dest / group.name.replace('.', '/') / artifact.name / 'maven-metadata.xml').write_text('<metadata><groupId>'+xml.escape(group.name)+'</groupId><artifactId>'+xml.escape(artifact.name)+'</artifactId><versioning><versions>'+''.join('<version>'+xml.escape(v)+'</version>' for v in versions)+'</versions></versioning></metadata>',encoding='utf-8')
print(f'Linked/copied {count} cached artifacts to {dest}')
