#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
RUN_DIR="${1:?Usage: run.sh /tmp/unique-run-directory [10000|100000|1000000|3000000]}"
MAX_REQUESTS="${2:-3000000}"
if [[ -e "$RUN_DIR" ]]; then echo 'Choose a new run directory; existing data will not be overwritten.' >&2; exit 1; fi
mkdir -p "$RUN_DIR/tools"
cd "$ROOT_DIR"
./mvnw -q -DskipTests clean package -l "$RUN_DIR/build.log"
python3 - "$ROOT_DIR" "$RUN_DIR" <<'PY'
import sys,zipfile,json,subprocess,sqlite3
from pathlib import Path
root,run=map(Path,sys.argv[1:]);jar=root/'target/multi-courier-aggregation-service-1.0.0.jar'
revision=subprocess.run(['git','rev-parse','HEAD'],cwd=root,capture_output=True,text=True)
(run/'environment.json').write_text(json.dumps({'source_commit':revision.stdout.strip() if revision.returncode==0 else 'unknown','java':subprocess.run(['java','-version'],capture_output=True,text=True).stderr.strip(),'seed_sqlite':sqlite3.sqlite_version},indent=2))
with zipfile.ZipFile(jar) as z:
 for name in z.namelist():
  if name.startswith(('BOOT-INF/lib/','BOOT-INF/classes/')):z.extract(name,run/'tools')
PY
CP="$RUN_DIR/tools/BOOT-INF/classes:$RUN_DIR/tools/BOOT-INF/lib/*:$RUN_DIR/tools/classes"
mkdir -p "$RUN_DIR/tools/classes"
javac -cp "$CP" -d "$RUN_DIR/tools/classes" tests/performance/java/*.java
python3 tests/performance/seed-and-run.py --run-dir "$RUN_DIR" --classpath "$CP" --max-requests "$MAX_REQUESTS" | tee "$RUN_DIR/progress.jsonl"
python3 - "$ROOT_DIR" "$RUN_DIR" <<'PYREPORT'
import sys,json,subprocess
from pathlib import Path
root,run=map(Path,sys.argv[1:]);environment=json.loads((run/'environment.json').read_text())
subprocess.run(['python3',str(root/'tests/performance/report.py'),'--run-dir',str(run),'--source-commit',environment['source_commit'],'--output',str(run/'load-test-results.md')],check=True)
PYREPORT
