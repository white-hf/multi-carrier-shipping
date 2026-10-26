from pathlib import Path
import json, statistics, math, argparse
from html import escape

# Reusable, dependency-free SVG charts. Data are aggregated from recorded JSON, never estimated.
def export_data(raw, target):
    seed=json.loads((raw/'seed-results.json').read_text())
    http=json.loads((raw/'http/http-results.json').read_text())
    scales=[]
    for stage in seed['stages']:
        n=stage['counts']['shipment_requests']; db=json.loads((raw/f'database-{n}.json').read_text())
        scales.append({'counts':stage['counts'],'database_gib':stage['database_bytes']/1024**3,
            'read_p95_ms':{k:max(t['p95_ms'] for t in db['warmed'][k]) for k in ['booking_idempotency','quote_by_id','request_quotes','overdue_first_page']},
            'contention':[{k:t[k] for k in ['workers','ops_per_second','p95_ms','p99_ms']} for t in db['contention']]})
    loads=[]
    for w in [1,2,4,8,16]:
        rates=[v for v in http['load'] if v['scenario']=='rates' and v['workers']==w]
        flows=[v for v in http['load'] if v['scenario']=='workflow' and v['workers']==w]
        success=lambda s:sum(v for k,v in s['outcomes'].items() if k.startswith('2'))/s['seconds']
        loads.append({'clients':w,'rate_2xx_per_second':statistics.median(success(v['rates']) for v in rates),
            'confirmed_workflows_per_second':statistics.median(v['bookings']['outcomes'].get('201',0)/v['bookings']['seconds'] for v in flows),
            'accepted_or_confirmed_workflows_per_second':statistics.median(success(v['workflow']) for v in flows),
            'rate_p95_ms':max(v['rates']['p95_ms'] for v in rates),
            'booking_p95_ms':max(v['bookings']['p95_ms'] for v in flows),
            'workflow_p95_ms':max(v['workflow']['p95_ms'] for v in flows)})
    target.write_text(json.dumps({'source_commit':'78c729e','source_directory':str(raw),'aggregation':{'throughput':'median of three ten-second repetitions','latency':'worst repetition percentile, not pooled percentile','database_reads':'worst of three warmed 1000-operation repetitions','contention':'one five-second run per level and dataset'},'scales':scales,'http':loads},indent=2)+'\n')

BLUE='#2563eb';GREEN='#14835d';ORANGE='#bf6b12';PURPLE='#7956b8';GRAY='#758397';INK='#172b4d'
class Figure:
    def __init__(self,title,subtitle,height=900):
        self.parts=[f'<svg xmlns="http://www.w3.org/2000/svg" width="1400" height="{height}" viewBox="0 0 1400 {height}" role="img" aria-labelledby="title desc">',f'<title id="title">{escape(title)}</title><desc id="desc">{escape(subtitle)}</desc>', '<style>text{font-family:Arial,Helvetica,sans-serif;fill:#172b4d}.title{font-size:25px;font-weight:700}.sub{font-size:16px;fill:#52627a}.panel{font-size:19px;font-weight:700}.tick{font-size:14px;fill:#52627a}.label{font-size:15px}.value{font-size:14px;font-weight:600}</style>',f'<rect width="1400" height="{height}" fill="#fff"/>']
        self.text(38,38,title,'title');self.text(38,67,subtitle,'sub')
    def text(self,x,y,t,cls='label',anchor='start',color=None):
        self.parts.append(f'<text x="{x:.2f}" y="{y:.2f}" class="{cls}" text-anchor="{anchor}"'+(f' style="fill:{color}"' if color else '')+f'>{escape(str(t))}</text>')
    def path(self,pts,color,width=2.5,dash=False):
        d=' '.join(('M' if i==0 else 'L')+f' {x:.2f} {y:.2f}' for i,(x,y) in enumerate(pts))
        self.parts.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}"'+(' stroke-dasharray="7 5"' if dash else '')+'/>')
    def rect(self,x,y,w,h,fill,stroke='none'):
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}" stroke="{stroke}" rx="5"/>')
    def axes(self,x,y,w,h,title,xs,labels,ymax,yticks,ylabel,xlabel,log=False):
        self.text(x,y-45,title,'panel');self.text(x,y-20,ylabel,'sub')
        for v in yticks:
            py=y+h-h*v/ymax;self.path([(x,py),(x+w,py)],'#e2e8f0',1);self.text(x-12,py+5,f'{v:g}','tick','end')
        self.path([(x,y),(x,y+h),(x+w,y+h)],'#9caabd',1)
        vals=[math.log10(a) for a in xs] if log else list(range(len(xs)))
        lo,hi=min(vals),max(vals)
        xpos=[x+20+(w-40)*(v-lo)/(hi-lo) for v in vals]
        for px,l in zip(xpos,labels):self.text(px,y+h+25,l,'tick','middle')
        self.text(x+w/2,y+h+53,xlabel,'sub','middle')
        return xpos,lambda v:y+h-h*v/ymax
    def series(self,xs,fy,values,color,labels=False,dash=False):
        self.path(list(zip(xs,map(fy,values))),color,dash=dash)
        for x,v in zip(xs,values):
            self.parts.append(f'<circle cx="{x:.2f}" cy="{fy(v):.2f}" r="4" fill="{color}"/>')
            if labels:self.text(x,fy(v)-11,f'{v:.1f}' if v>=1 else f'{v:.3f}','value','middle',color)
    def legend(self,x,y,items):
        for i,(label,color,dash) in enumerate(items):
            self.path([(x,y+i*25),(x+27,y+i*25)],color,dash=dash);self.text(x+37,y+5+i*25,label,'label')
    def save(self,path):
        self.parts.append('</svg>');path.write_text('\n'.join(self.parts)+'\n')
        import xml.etree.ElementTree as ET
        ET.parse(path)

def draw(data, out):
    out.mkdir(parents=True,exist_ok=True);sc=data['scales'];http=data['http'];workers=[1,2,4,8,16];wl=list(map(str,workers))
    f=Figure('1. Database size vs. indexed read latency','Historical run 78c729e | Local Mac: 16 GiB RAM, 10 logical CPUs | SQLite, four connections',650)
    quotes=[s['counts']['shipment_quotes'] for s in sc];labs=['50K','500K','5M','15M']
    xs,fy=f.axes(90,160,560,260,'Indexed reads remained below 2 ms',quotes,labs,2,[0,.5,1,1.5,2],'Worst repetition P95 (ms)','Quote rows (logarithmic scale)',True)
    defs=[('booking_idempotency','Booking key',BLUE),('quote_by_id','Quote ID',GREEN),('request_quotes','Request quote list',ORANGE),('overdue_first_page','Overdue page',PURPLE)]
    for key,name,color in defs:f.series(xs,fy,[s['read_p95_ms'][key] for s in sc],color)
    f.legend(110,510,[(name,color,False) for key,name,color in defs])
    xs,fy=f.axes(785,160,540,260,'Storage grew with the dataset',quotes,labs,12,[0,3,6,9,12],'Database file size (GiB)','Quote rows (logarithmic scale)',True)
    f.series(xs,fy,[s['database_gib'] for s in sc],BLUE,True)
    f.text(785,520,'Largest stage: 3M requests / 15M quotes','label');f.text(785,548,'1.5M bookings / 150K recovery tasks','label')
    f.text(38,627,'Database-only timings: warmed cache, connection checkout and mapping included. This is not an HTTP workload.','sub')
    f.save(out/'database-scale.svg')

    f=Figure('2. HTTP concurrency vs. throughput and latency','Historical run 78c729e | 20 ms local stub | Rate/create slots: 4/2 | Separate small database',1100)
    xs,fy=f.axes(90,155,560,250,'Rate throughput reaches a plateau',workers,wl,200,[0,50,100,150,200],'Rate 2xx responses / second','Concurrent clients')
    f.series(xs,fy,[s['rate_2xx_per_second'] for s in http],BLUE,True)
    xs,fy=f.axes(785,155,540,250,'Accepted is not the same as confirmed',workers,wl,120,[0,30,60,90,120],'Rate-to-booking workflows / second','Concurrent clients')
    f.series(xs,fy,[s['accepted_or_confirmed_workflows_per_second'] for s in http],GRAY,False,True)
    f.series(xs,fy,[s['confirmed_workflows_per_second'] for s in http],GREEN,True)
    f.legend(800,485,[('Confirmed creation (201)',GREEN,False),('Accepted or confirmed (all 2xx)',GRAY,True)])
    f.text(90,492,'Throughput = median of three runs per level.','sub')
    xs,fy=f.axes(90,655,560,235,'Rate endpoint tail latency increases',workers,wl,300,[0,75,150,225,300],'Worst repetition P95 (ms)','Concurrent clients')
    f.series(xs,fy,[s['rate_p95_ms'] for s in http],BLUE,True)
    xs,fy=f.axes(785,655,540,235,'Booking and full-workflow latency',workers,wl,300,[0,75,150,225,300],'Worst repetition P95 (ms)','Concurrent clients')
    f.series(xs,fy,[s['booking_p95_ms'] for s in http],ORANGE,True)
    f.series(xs,fy,[s['workflow_p95_ms'] for s in http],PURPLE,False,True)
    f.legend(800,965,[('POST /api/bookings',ORANGE,False),('Rate + booking workflow',PURPLE,True)])
    f.text(38,1032,'Rate latency: POST /api/quotes, rates-only scenario.','sub')
    f.text(38,1062,'Closed-loop clients; three 10-second runs per level. Latency includes pending outcomes; it is not success-only P95.','sub')
    f.save(out/'http-concurrency.svg')

    f=Figure('3. Database concurrency vs. throughput and tail latency','Historical run 78c729e | Largest dataset: 3M requests / 15M quotes | Four database connections',640)
    c=sc[-1]['contention']
    xs,fy=f.axes(90,160,560,260,'Four workers outperformed sixteen',workers,wl,25000,[0,5000,10000,15000,20000,25000],'Repository operations / second','Concurrent workers')
    vals=[v['ops_per_second'] for v in c];f.series(xs,fy,vals,BLUE)
    for x,v in zip(xs,vals):f.text(x,fy(v)-12,f'{v:,.0f}','value','middle',BLUE)
    xs,fy=f.axes(785,160,540,260,'P99 rose as contention increased',workers,wl,20,[0,5,10,15,20],'Mixed-operation latency (ms)','Concurrent workers')
    f.series(xs,fy,[v['p99_ms'] for v in c],ORANGE,True)
    f.series(xs,fy,[v['p95_ms'] for v in c],GREEN)
    f.legend(800,510,[('P99: 99% of measured attempts',ORANGE,False),('P95: 95% of measured attempts',GREEN,False)])
    f.text(90,520,'80% reads / 20% conditional-write attempts.','label');f.text(90,548,'CAS conflicts count as attempts, not successful writes.','label')
    f.text(38,610,'Five seconds per level; local contention evidence, not a long-running capacity limit or multi-host result.','sub')
    f.save(out/'database-concurrency.svg')

if __name__=='__main__':
    p=argparse.ArgumentParser(description='Plot recorded shipping load results without third-party dependencies.')
    p.add_argument('--data',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True)
    p.add_argument('--raw-dir',type=Path,help='Optional: rebuild compact chart data from original JSON before plotting.')
    a=p.parse_args()
    if a.raw_dir:export_data(a.raw_dir,a.data)
    draw(json.loads(a.data.read_text()),a.output_dir)
