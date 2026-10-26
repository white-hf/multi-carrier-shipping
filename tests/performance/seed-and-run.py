import sqlite3,time,uuid,hashlib,json,os,sys,argparse,subprocess,shutil,resource
from pathlib import Path
parser=argparse.ArgumentParser(description="Build isolated synthetic data, then run JDBC and HTTP load probes.")
parser.add_argument('--run-dir',required=True)
parser.add_argument('--classpath',required=True)
parser.add_argument('--max-requests',type=int,default=3000000,choices=[10000,100000,1000000,3000000])
args=parser.parse_args()
run=Path(args.run_dir).resolve()
if not (str(run).startswith('/tmp/') or str(run).startswith('/private/tmp/')):raise SystemExit('Disposable /tmp output required')
run.mkdir(parents=True,exist_ok=True)
if (run/'capacity.db').exists():raise SystemExit('Refusing to overwrite an existing database')
root=Path(__file__).resolve().parents[2]/'src/main/resources/db/shipping'
result={'sqlite':sqlite3.sqlite_version,'settings':{'journal_mode':'WAL','synchronous':'FULL','foreign_keys':True,'batch_requests':1000,'indexes':'all current baseline indexes maintained during insert'},'stages':[],'run_directory':str(run),'seed_cache_kib':65536,'max_requests':args.max_requests,'started_at':time.strftime('%Y-%m-%dT%H:%M:%S%z')}
def uid(n):return str(uuid.UUID(int=((1792800000000+n)<<80)|(7<<76)|(2<<62)|(n&((1<<62)-1))))
def user(n):return 'guest-'+hashlib.sha256(str(n%1000).encode()).hexdigest()[:24]
directory=str(run)
if True:
 path=Path(directory)/'capacity.db';c=sqlite3.connect(path)
 c.execute('PRAGMA journal_mode=WAL');c.execute('PRAGMA synchronous=FULL');c.execute('PRAGMA foreign_keys=ON');c.execute('PRAGMA cache_size=-65536')
 c.execute('CREATE TABLE schema_migrations(version TEXT PRIMARY KEY,checksum TEXT NOT NULL)')
 for file in sorted(root.glob('V*.sql')):
  c.executescript(file.read_text());c.execute('INSERT INTO schema_migrations VALUES (?,?)',(file.name,hashlib.sha256(file.read_bytes()).hexdigest()));c.commit()
 tables=['shipment_requests','shipment_quotes','shipment_bookings','booking_reconciliation_tasks']
 columns={t:[r[1] for r in c.execute('PRAGMA table_info('+t+')')] for t in tables}
 sql={t:'INSERT INTO '+t+' ('+','.join(columns[t])+') VALUES ('+','.join('?' for _ in columns[t])+')' for t in tables}
 peak=0;start=time.perf_counter();last=0
 for target in [v for v in [10000,100000,1000000,3000000] if v<=args.max_requests]:
  nested_bytes=sum(f.stat().st_size for d in run.iterdir() if d.is_dir() for f in d.rglob('*') if f.is_file())
  stage_start=time.perf_counter()
  print(json.dumps({'event':'seed_stage_started','target':target}),flush=True)
  for low in range(last,target,1000):
   if shutil.disk_usage(run).free<15*1024**3:raise RuntimeError('15 GiB free-space guard reached')
   if time.perf_counter()-stage_start>7200:raise RuntimeError('Two-hour seed-stage budget reached')
   batches={t:[] for t in tables}
   for i in range(low,min(low+1000,target)):
    u=user(i);rid=uid(i);now=1792800000000-(i%365)*86400000;finger=hashlib.sha256(str(i).encode()).hexdigest()
    r={'request_id':rid,'user_id':u,'parcel_length':'12.3456','parcel_width':'10.25','parcel_height':'8.125','parcel_weight':'5.75','dimension_unit':'IN','weight_unit':'LB','package_type':'PACKAGE','shipping_date':'2026-10-24','state':'NO_RATES' if i%10==0 else 'READY','created_at':now,'updated_at':now,'idempotency_key':uid(i+10000000),'content_fingerprint':finger,'rate_version':0,'rate_deadline_at':None}
    for prefix in ['sender','recipient']:
     a={'contact_name':'Alexandra Example '+str(i%100),'company_name':'Example Distribution Services Ltd.','address_line1':str(100+i%900)+' King Street West','address_line2':'Suite '+str(i%1000),'city':'Toronto' if prefix=='sender' else 'Halifax','province':'ON' if prefix=='sender' else 'NS','postal_code':'M5V 3L9' if prefix=='sender' else 'B3J 3K1','country_code':'CA','phone':'+1-416-555-0100','email':'contact'+str(i%1000)+'@example.invalid','residential':i%2}
     r.update({prefix+'_'+k:v for k,v in a.items()})
    batches[tables[0]].append(tuple(r[k] for k in columns[tables[0]]))
    for j in range([0,5,5,5,5,6,6,6,6,6][i%10]):
     q={'quote_id':uid(100000000+i*6+j),'request_id':rid,'user_id':u,'provider_id':'sandbox','account_alias':'primary-shipping-account','provider_quote_id':'provider-quote-'+uid(i*6+j),'carrier_id':'carrier-'+str(j),'carrier_name':'Example Carrier '+str(j),'service_id':'ground-'+str(j),'service_name':'Domestic Expedited Ground','total_minor':2735+j*100,'currency':'CAD','transit_days':j+1,'expires_at':now+600000}
     batches[tables[1]].append(tuple(q[k] for k in columns[tables[1]]))
    if i%2==1:
     bid=uid(200000000+i);pending=i%100<5
     b={'booking_id':bid,'user_id':u,'idempotency_key':uid(300000000+i),'request_hash':finger,'request_id':rid,'quote_id':uid(100000000+i*6),'provider_id':'sandbox','account_alias':'primary-shipping-account','provider_quote_id':'provider-quote-'+uid(i*6),'carrier_id':'carrier-0','service_id':'ground-0','accepted_minor':2735,'currency':'CAD','quote_expires_at':now+600000,'status':('PENDING' if i%100==1 else 'PENDING_INQUIRY') if pending else 'CONFIRMED','provider_shipment_id':None if pending else str(100000000+i),'tracking_number':None if pending else 'TRACKING-'+str(i),'label_url':None if pending else 'https://labels.example.invalid/shipments/'+bid+'/label.pdf?signature='+finger,'label_status':'PENDING' if pending else 'AVAILABLE','error_code':'CARRIER_OUTCOME_UNKNOWN' if pending and i%100!=1 else None,'submission_deadline_at':now+60000,'created_at':now,'updated_at':now,'version':1}
     batches[tables[2]].append(tuple(b[k] for k in columns[tables[2]]))
     if i%20==1:
      t={'task_id':uid(400000000+i),'user_id':u,'booking_id':bid,'booking_locator':u,'provider_id':'sandbox','account_alias':'primary-shipping-account','customer_reference':bid,'state':'WAITING' if (i//20)%5==0 else 'PAUSED','available_at':now+(400*86400000 if (i//20)%7==0 else 60000),'last_error_code':'AUTHORITATIVE_LOOKUP_UNSUPPORTED','created_at':now,'updated_at':now}
      batches[tables[3]].append(tuple(t[k] for k in columns[tables[3]]))
   with c:
    for table in tables:c.executemany(sql[table],batches[table])
   peak=max(peak,nested_bytes+sum(p.stat().st_size for p in Path(directory).iterdir() if p.is_file()))
   if peak>22*1024**3:raise RuntimeError('22 GiB run-storage guard reached')
   if (low+1000)%10000==0:print(json.dumps({'event':'seed_progress','requests':low+1000,'target':target,'stage_seconds':round(time.perf_counter()-stage_start,2),'total_bytes':peak,'free_bytes':shutil.disk_usage(run).free}),flush=True)
  before=time.perf_counter();c.execute('ANALYZE');c.execute('PRAGMA optimize');c.execute('PRAGMA wal_checkpoint(TRUNCATE)').fetchall();checkpoint=time.perf_counter()-before
  counts={t:c.execute('SELECT count(*) FROM '+t).fetchone()[0] for t in tables}
  result['stages'].append({'counts':counts,'database_bytes':path.stat().st_size,'peak_total_bytes':peak,'stage_seconds_including_checkpoint':time.perf_counter()-stage_start,'cumulative_seconds':time.perf_counter()-start,'checkpoint_seconds':checkpoint})
  print(json.dumps({'event':'seed_stage_complete',**result['stages'][-1]}),flush=True)
  (run/'seed-results.json').write_text(json.dumps(result,indent=2))
  c.close()
  with (run/f'database-{target}.log').open('w') as log:
   subprocess.run(['java','-Xmx768m','-cp',args.classpath,'DatabaseLoadRunner',str(path),str(target),str(run/f'database-{target}.json')],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=1800)
  with (run/f'query-plans-{target}.log').open('w') as log:
   subprocess.run(['java','-Xmx256m','-cp',args.classpath,'QueryPlanAudit',str(path),str(run/f'query-plans-{target}.json')],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=120)
  bench=json.loads((run/f'database-{target}.json').read_text())
  if not bench['correctness_pass']:raise RuntimeError('Database correctness gate failed')
  print(json.dumps({'event':'database_stage_complete','requests':target,'latency_budgets_pass':bench['latency_budgets_pass']}),flush=True)
  if target==100000:
   with (run/'http.log').open('w') as log:
    subprocess.run(['java','-Xmx768m','-cp',args.classpath,'HttpLoadRunner',str(run/'http')],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=900)
   with (run/'http/database-audit.log').open('w') as log:
    subprocess.run(['java','-Xmx256m','-cp',args.classpath,'QueryPlanAudit',str(run/'http/http.db'),str(run/'http/database-audit.json')],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=120)
   print(json.dumps({'event':'http_stage_complete'}),flush=True)
  if target==args.max_requests:
   with (run/'cas-write-budget.log').open('w') as log:
    subprocess.run(['java','-Xmx768m','-cp',args.classpath,'CasWriteProbe',str(path),str(target),str(run/'cas-write-budget.json')],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=180)
  c=sqlite3.connect(path);c.execute('PRAGMA journal_mode=WAL');c.execute('PRAGMA synchronous=FULL');c.execute('PRAGMA foreign_keys=ON');c.execute('PRAGMA cache_size=-65536')
  last=target
 # Allocation is already captured by the JDBC runner; avoid a duplicate full-file walk.
 result['allocation_source']=f'database-{last}.json allocation'
 c.close()
result['completed_at']=time.strftime('%Y-%m-%dT%H:%M:%S%z');result['peak_python_rss_bytes']=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
(run/'seed-results.json').write_text(json.dumps(result,indent=2))
print(json.dumps({'event':'all_stages_complete','run_directory':str(run)}),flush=True)
