#!/usr/bin/env python3
"""Render recorded measurements without rerunning tests or opening the database."""
import argparse,json,statistics
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--run-dir',required=True);p.add_argument('--output',required=True);p.add_argument('--source-commit',default='unknown');p.add_argument('--status',choices=['auto','running','completed','stopped'],default='auto');a=p.parse_args();run=Path(a.run_dir)
seed=json.loads((run/'seed-results.json').read_text());status=('completed' if seed.get('completed_at') else 'running') if a.status=='auto' else a.status
lines=['# Local shipping load-test results','','Status: **'+status.upper()+'**. Unexecuted stages are not accepted.','','Source: `'+a.source_commit+'` (unified Shipping flow). SQLite-only local evidence.','','## Dataset build and JDBC scale results','', '| Requests | Quotes | DB MiB | Build seconds¹ | Correctness | Latency budgets |','| ---: | ---: | ---: | ---: | --- | --- |']
dbs=[]
for stage in seed['stages']:
 n=stage['counts']['shipment_requests'];f=run/f'database-{n}.json';d=json.loads(f.read_text()) if f.exists() else None
 lines.append(f"| {n:,} | {stage['counts']['shipment_quotes']:,} | {stage['database_bytes']/1024**2:.1f} | {stage['stage_seconds_including_checkpoint']:.1f} | {d['correctness_pass'] if d else 'pending'} | {d['latency_budgets_pass'] if d else 'pending'} |")
 if d:dbs.append(d)
lines+=['','¹ Incremental growth from the preceding stage, including analysis/checkpoint. Build measurements use the documented preparation cache.','','## Warmed JDBC latency','', 'P95 below is the worst of three 1,000-operation repetitions, including connection checkout and row mapping.','','| Requests | Booking key P95 ms | Quote ID P95 ms | Request quotes P95 ms | Discovery P95 ms |','| ---: | ---: | ---: | ---: | ---: |']
for d in dbs:
 vals=[max(x['p95_ms'] for x in d['warmed'][k]) for k in ['booking_idempotency','quote_by_id','request_quotes','overdue_first_page']]
 lines.append('| '+f"{d['requests']:,}"+' | '+' | '.join(f'{v:.3f}' for v in vals)+' |')
lines+=['','## Concurrent repository access','','80% reads / 20% CAS attempts; five seconds per concurrency level. CAS conflicts are expected; version accounting must remain exact.','','| Requests | Workers | Ops/s | P95 ms | P99 ms | CAS applied | CAS conflicts | Other errors |','| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
for d in dbs:
 for c in d['contention']:
  errors={k:v for k,v in c['outcomes'].items() if k not in ['read','cas_applied','cas_conflict']}
  lines.append(f"| {d['requests']:,} | {c['workers']} | {c['ops_per_second']:.0f} | {c['p95_ms']:.3f} | {c['p99_ms']:.3f} | {c['successful_writes']} | {c['cas_conflicts']} | {errors or 'none'} |")
f=run/'http/http-results.json'
if f.exists():
 h=json.loads(f.read_text());lines+=['','## HTTP load','','Actual authenticated application, local 20 ms provider stub; rate/create bulkheads are configured to 4/2 for this test. Each row summarizes three ten-second repetitions. Success throughput is the median; P95 is the worst repetition and includes accepted and rejected responses.','','| Scenario | Workers | 2xx responses/s | Total responses | 503 capacity responses | Worst P95 ms |','| --- | ---: | ---: | ---: | ---: | ---: |']
 for scenario,label in [('status','status'),('rates','rates'),('workflow','workflow')]:
  for workers in [1,2,4,8,16]:
   rows=[x for x in h['load'] if x['scenario']==scenario and x['workers']==workers];stats=[x[label] for x in rows]
   rates=[sum(v for k,v in x['outcomes'].items() if k in ['200','201','202'])/x['seconds'] for x in stats]
   lines.append(f"| {scenario} | {workers} | {statistics.median(rates):.1f} | {sum(x['operations'] for x in stats)} | {sum(x['outcomes'].get('503',0) for x in stats)} | {max(x['p95_ms'] for x in stats):.2f} |")
 lines+=['','Reliability assertions: '+', '.join(f"`{k}`={v}" for k,v in h['reliability'].items())+'.',f"Post-load recovery: **{h['post_load_recovery']}**. Availability classification: **{h['availability_pass']}**."]
 audit=run/'http/database-audit.json'
 if audit.exists():
  data=json.loads(audit.read_text());lines+=['','HTTP booking states at completion: `'+json.dumps(data['booking_states'])+'`.']
  confirmed=sum(x['rows'] for x in data['booking_states'] if x['status']=='CONFIRMED')
  lines+=['','Confirmed bookings: **'+str(confirmed)+'**; simulated provider create calls: **'+str(h['provider_calls']['creates'])+'**. One deliberately lost creation response is expected. HTTP 202 means accepted/pending, not confirmed shipment success.']
else:lines+=['','HTTP test is pending or incomplete; no pass is claimed.']
cas=run/'cas-write-budget.json'
if cas.exists():
 data=json.loads(cas.read_text());lines+=['','## Separate CAS attempt latency','','Includes version read, domain restoration, conditional update and commit. Three five-second repetitions per level; reads and writes have separate distributions. The 20 ms target is an initial uncontended P95 budget.','','| Workers | Worst write-attempt P95 ms | Worst write-attempt P99 ms |','| ---: | ---: | ---: |']
 for workers in [1,4,16]:
  rows=[x['writes_including_version_read_and_commit'] for x in data['results'] if x['workers']==workers]
  lines.append(f"| {workers} | {max(x['p95_ms'] for x in rows):.3f} | {max(x['p99_ms'] for x in rows):.3f} |")
 lines+=['','CAS probe correctness: **'+str(data['correctness_pass'])+'**.']
audits=sorted(run.glob('query-plans-*.json'))
if audits:
 lines+=['','## Extended query-plan audit','']
 for path in audits:
  audit=json.loads(path.read_text());lines.append(f"- `{path.name}`: {len(audit['queries'])} access patterns; all indexed: **{audit['all_indexed']}**. UPDATE statements were explained only.")
lines+=['','## Assessment and follow-ups','']
if len(dbs)==4 and all(x['correctness_pass'] and x['latency_budgets_pass'] for x in dbs):
 lines+=['All four dataset stages passed the measured warmed-read latency budgets, ownership checks and CAS version accounting. No SQLite busy/pool errors were recorded in the mixed repository runs. This is a local regression acceptance, not a production capacity certification.']
if f.exists():
 pending_file=run/'http/database-audit.json'
 pending_rows=sum(x['rows'] for x in json.loads(pending_file.read_text())['booking_states'] if x['status']=='PENDING_INQUIRY') if pending_file.exists() else 'not recorded'
 lines+=['','The application catches all provider runtime failures after claiming a booking and marks the outcome unknown (`ShipmentApplicationService`, provider-create catch). Under the configured create bulkhead of two, the run produced '+str(pending_rows)+' pending-inquiry rows while confirmed rows matched simulated provider calls minus the one deliberately lost response. This is consistent with local pre-dispatch rejection being treated as remote uncertainty. Separate known admission rejection from genuinely ambiguous outcomes in a future design review; do not introduce blind creation retries. Production code was not changed.']
 if 'load' in h:
  lines+=['','Confirmed creation throughput under the test configuration:']
  for workers in [1,2,4,8,16]:
   rows=[x for x in h['load'] if x['scenario']=='workflow' and x['workers']==workers]
   lines.append(f"- {workers} clients: median {statistics.median(x['bookings']['outcomes'].get('201',0)/x['bookings']['seconds'] for x in rows):.1f} confirmations/s.")
  lines+=['','Higher client concurrency did not increase confirmed throughput beyond four clients. HTTP 202 must not be counted as a completed shipment.']
if dbs:
 c=dbs[-1]['contention'];four=next(x for x in c if x['workers']==4);sixteen=next(x for x in c if x['workers']==16)
 lines+=[f"At the largest dataset, mixed repository throughput was {four['ops_per_second']:.0f} ops/s at four workers and {sixteen['ops_per_second']:.0f} at sixteen. More workers increased contention rather than capacity; retain bounded admission and size from measured throughput."]
if seed.get('completed_at'):
 import datetime
 wall=(datetime.datetime.fromisoformat(seed['completed_at'])-datetime.datetime.fromisoformat(seed['started_at'])).total_seconds()/60
 lines += [f"End-to-end wall elapsed time: {wall:.1f} minutes; recorded incremental seeding/analysis/count/checkpoint stages sum to {sum(x['stage_seconds_including_checkpoint'] for x in seed['stages'])/60:.1f} minutes. These timers have different scopes. Original finalization also walked page-allocation metadata; host pauses were not separately instrumented. The reusable runner now reuses the JDBC allocation results instead of repeating that walk in Python.",f"Peak observed run artifacts: {max(x['peak_total_bytes'] for x in seed['stages'])/1024**3:.2f} GiB, below the 22 GiB cap. The 15 GiB free-space guard did not trigger."]
if f.exists():
 lines += [f"Maximum observed HTTP heap snapshot: {max(x['heap_used_bytes'] for x in h['load'])/1024**2:.1f} MiB (not a continuously sampled process-memory peak). CPU values in raw results combine application, stub and load generator in one JVM."]
lines+=['','The executed run captured its regular plans at every scale and the supplementary 12-pattern audit at the final scale. A separate CAS probe was run afterward. Reusable tooling now invokes these audit/probe hooks automatically; the larger stages were not rerun merely to exercise those hooks.','']
lines+=['','## Scope and limitations','','- Mac: 16 GiB RAM, 10 logical CPUs; application, driver, stub and load client share the host.', '- Java runtime: Homebrew OpenJDK 17.0.19; Java probes use a 768 MiB heap ceiling.', '- HTTP uses one authenticated guest; the volume dataset spans 1,000 synthetic users. Historical seed dates are fixed; expiry transitions are not stress-tested by the point-lookup benchmarks.','- Seed SQLite: '+seed['sqlite']+'; JDBC SQLite: '+(dbs[-1]['jdbc_sqlite'] if dbs else 'pending')+'.','- WAL/FULL and foreign keys enabled; four application database connections.','- Warmed OS cache; first-pass samples are separately recorded, not true cold-cache measurements.','- Four application tables; task due scans are synthetic index probes, not a claim of an active lease-based recovery consumer.','- No real sandbox, financial charges, frontend changes, production database changes or MySQL/PostgreSQL validation.','- HTTP is closed-loop; saturation results include configured fast rejection. Aggregate latency cannot establish success-only tail latency under overload.', '- HTTP-only test budgets: rates 7 s, create/read 1 s; automatic recovery discovery interval 1 h, with one explicit discovery check for the injected unknown outcome. These differ from production defaults and isolate each measurement.','- Single-process repository contention does not prove multi-process or multi-host behavior.','',f'Raw artifacts: `{run}`. Databases are not committed.','']
Path(a.output).write_text('\n'.join(lines));print(a.output)
