import com.enterprise.shipping.booking.infrastructure.persistence.JdbcShipmentStore;
import com.enterprise.shipping.quote.infrastructure.persistence.JdbcQuoteStore;
import com.enterprise.shipping.shared.infrastructure.persistence.SqliteDataSources;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Explicitly invoked local benchmark, excluded from the normal unit-test lifecycle. */
public class DatabaseLoadRunner {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long EPOCH = 1792800000000L;
    static String id(long n) { return new UUID(((EPOCH+n)<<16)|0x7000L, 0x8000000000000000L|n).toString(); }
    static String user(long n) {
        try { return "guest-"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Long.toString(n%1000).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,24); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static class Stats {
        long count, rows; final ArrayList<Double> sample = new ArrayList<>();
        final Map<String,Long> outcomes = new TreeMap<>();
        synchronized void add(long nanos, long returned, String outcome) {
            count++; rows+=returned; outcomes.merge(outcome,1L,Long::sum);
            double value=nanos/1e6;
            if(sample.size()<100000) sample.add(value);
            else { long j=ThreadLocalRandom.current().nextLong(count); if(j<100000) sample.set((int)j,value); }
        }
        synchronized Map<String,Object> report(double seconds) {
            sample.sort(Double::compareTo);
            Map<String,Object> r=new LinkedHashMap<>();
            r.put("operations",count);r.put("latency_samples",sample.size());r.put("rows_returned",rows);
            r.put("seconds",seconds);r.put("ops_per_second",count/Math.max(.001,seconds));
            for(int p:new int[]{50,95,99}) r.put("p"+p+"_ms",sample.isEmpty()?null:sample.get(Math.min(sample.size()-1,(int)Math.ceil(sample.size()*p/100.)-1)));
            r.put("outcomes",new TreeMap<>(outcomes));return r;
        }
    }
    interface Work { int run(int i) throws Exception; }
    static Map<String,Object> measure(Work work,int count) throws Exception {
        Stats s=new Stats();long start=System.nanoTime();
        for(int i=0;i<count;i++){long t=System.nanoTime();int rows=work.run(i);s.add(System.nanoTime()-t,rows,"ok");}
        return s.report((System.nanoTime()-start)/1e9);
    }
    static String columns(Class<?> c,String field) throws Exception {
        var f=c.getDeclaredField(field);f.setAccessible(true);return (String)f.get(null);
    }
    public static void main(String[] args) throws Exception {
        Path file=Path.of(args[0]).toAbsolutePath();int size=Integer.parseInt(args[1]);Path out=Path.of(args[2]);
        if(!file.startsWith(Path.of("/tmp")) && !file.startsWith(Path.of("/private/tmp"))) throw new IllegalArgumentException("Disposable /tmp database required");
        Map<String,Object> report=new LinkedHashMap<>();
        try(var ds=SqliteDataSources.open(file.toString(),"load-db",4,3000,"db/shipping/bootstrap.sql")) {
            var jdbc=new JdbcTemplate(ds);var quotes=new JdbcQuoteStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
            var bookings=new JdbcShipmentStore(jdbc);Random random=new Random(4927);int[] keys=new int[3000];
            for(int i=0;i<keys.length;i++) keys[i]=random.nextInt(size);
            report.put("jdbc_sqlite",jdbc.queryForObject("select sqlite_version()",String.class));
            report.put("requests",size);report.put("pool_size",4);report.put("synchronous",jdbc.queryForObject("PRAGMA synchronous",Integer.class));
            report.put("pragmas",Map.of("busy_timeout",jdbc.queryForObject("PRAGMA busy_timeout",Integer.class),"cache_size",jdbc.queryForObject("PRAGMA cache_size",Integer.class),"journal_mode",jdbc.queryForObject("PRAGMA journal_mode",String.class)));
            Map<String,Work> reads=new LinkedHashMap<>();
            reads.put("pool_checkout",i->{try(var c=ds.getConnection()){return 0;}});
            reads.put("request_by_id",i->{int n=keys[i%keys.length];return quotes.request(user(n),id(n)).isPresent()?1:0;});
            reads.put("request_idempotency",i->{int n=keys[i%keys.length];return quotes.byKey(user(n),id(n+10000000)).isPresent()?1:0;});
            reads.put("booking_by_id",i->{int n=2*(keys[i%keys.length]%(size/2))+1;return bookings.byId(user(n),id(200000000L+n)).isPresent()?1:0;});
            reads.put("booking_idempotency",i->{int n=2*(keys[i%keys.length]%(size/2))+1;return bookings.byKey(user(n),id(300000000L+n)).isPresent()?1:0;});
            reads.put("booking_by_request",i->{int n=2*(keys[i%keys.length]%(size/2))+1;return bookings.byRequest(user(n),id(n)).isPresent()?1:0;});
            reads.put("quote_by_id",i->{int n=keys[i%keys.length];if(n%10==0)n++;return quotes.find(user(n),id(100000000L+n*6L)).isPresent()?1:0;});
            reads.put("request_quotes",i->{int n=keys[i%keys.length];return quotes.quotes(user(n),id(n)).size();});
            reads.put("wrong_owner",i->{int n=keys[i%keys.length];return quotes.request("not-owner",id(n)).isPresent()?1:0;});
            reads.put("missing_request",i->quotes.request("not-owner",id(900000000L+i)).isPresent()?1:0);
            reads.put("overdue_first_page",i->bookings.overdue(EPOCH+86400000,0,"",100).size());
            reads.put("overdue_deep_page",i->bookings.overdue(EPOCH+86400000,EPOCH-86400000L*30,id(size/2),100).size());
            String due="SELECT task_id,user_id,booking_id FROM booking_reconciliation_tasks WHERE state='WAITING' AND available_at<=? ORDER BY available_at,task_id LIMIT ?";
            reads.put("synthetic_task_due",i->jdbc.queryForList(due,EPOCH+86400000,100).size());
            Map<String,Object> first=new LinkedHashMap<>(),warm=new LinkedHashMap<>();boolean budgets=true;boolean readCorrect=true;
            for(var e:reads.entrySet()) {
                first.put(e.getKey(),measure(e.getValue(),100));
                measure(e.getValue(),200);
                List<Map<String,Object>> reps=new ArrayList<>();
                for(int r=0;r<3;r++) {
                    var m=measure(e.getValue(),1000);reps.add(m);
                    double budget=e.getKey().contains("overdue")||e.getKey().contains("task_due")?50:e.getKey().equals("request_quotes")?20:10;
                    if((double)m.get("p95_ms")>budget)budgets=false;
                }
                if(Set.of("request_by_id","request_idempotency","booking_by_id","booking_idempotency","booking_by_request","quote_by_id").contains(e.getKey()))
                    for(var m:reps)readCorrect &= ((Number)m.get("rows_returned")).longValue()==1000;
                if(Set.of("wrong_owner","missing_request").contains(e.getKey()))
                    for(var m:reps)readCorrect &= ((Number)m.get("rows_returned")).longValue()==0;
                warm.put(e.getKey(),reps);
                System.out.println("DB "+size+" "+e.getKey()+" "+JSON.writeValueAsString(reps.get(0)));
            }
            report.put("first_pass_not_cold_os",first);report.put("warmed",warm);report.put("latency_budgets_pass",budgets);
            Map<String,Object> plans=new LinkedHashMap<>();String rc=columns(JdbcQuoteStore.class,"REQUEST_SELECT_COLUMNS"),bc=columns(JdbcShipmentStore.class,"BOOKING_SELECT_COLUMNS");
            String[] statements={"SELECT "+rc+" FROM shipment_requests WHERE user_id=? AND idempotency_key=?",
                    "SELECT "+bc+" FROM shipment_bookings WHERE user_id=? AND idempotency_key=?",
                    "SELECT "+bc+" FROM shipment_bookings WHERE user_id=? AND request_id=?",
                    "SELECT quote_id,total_minor FROM shipment_quotes WHERE user_id=? AND request_id=?"};
            boolean indexed=true;
            for(String sql:statements){var rows=jdbc.queryForList("EXPLAIN QUERY PLAN "+sql,user(1),id(1));plans.put(sql,rows);if(rows.stream().noneMatch(m->m.get("detail").toString().contains("SEARCH")))indexed=false;}
            String overdue="SELECT "+bc+" FROM shipment_bookings WHERE status IN ('PENDING','PENDING_INQUIRY') AND submission_deadline_at<=? AND (submission_deadline_at>? OR (submission_deadline_at=? AND booking_id>?)) ORDER BY submission_deadline_at,booking_id LIMIT ?";
            plans.put("overdue",jdbc.queryForList("EXPLAIN QUERY PLAN "+overdue,EPOCH+86400000,0,0,"",100));
            plans.put("synthetic_task_due",jdbc.queryForList("EXPLAIN QUERY PLAN "+due,EPOCH+86400000,100));report.put("plans",plans);report.put("indexed_points_pass",indexed);
            List<Object> contention=new ArrayList<>();boolean correctness=indexed && readCorrect;report.put("read_invariants_pass",readCorrect);
            for(int workers:new int[]{1,2,4,8,16}) {
                long initial=bookings.byId(user(1),id(200000001)).orElseThrow().version();
                Stats s=new Stats();AtomicLong writes=new AtomicLong(),conflicts=new AtomicLong(),failures=new AtomicLong();
                ExecutorService pool=Executors.newFixedThreadPool(workers);CountDownLatch ready=new CountDownLatch(workers),go=new CountDownLatch(1);
                List<Future<?>> futures=new ArrayList<>();long[] end={0};
                for(int w=0;w<workers;w++){final int offset=w;futures.add(pool.submit(()->{
                    ready.countDown();try{go.await();int k=offset;
                        while(System.nanoTime()<end[0]){long t=System.nanoTime();try{
                            if((k++%5)==0){var row=bookings.byId(user(1),id(200000001)).orElseThrow();var booking=row.restore();booking.markOutcomeUnknown();
                                boolean applied=bookings.outcome(row,booking,System.currentTimeMillis());if(applied)writes.incrementAndGet();else conflicts.incrementAndGet();s.add(System.nanoTime()-t,0,applied?"cas_applied":"cas_conflict");
                            }else{int n=keys[k%keys.length];quotes.request(user(n),id(n));s.add(System.nanoTime()-t,1,"read");}
                        }catch(Exception failure){failures.incrementAndGet();s.add(System.nanoTime()-t,0,failure.getClass().getSimpleName());}}
                    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                }));}
                ready.await();long start=System.nanoTime();end[0]=start+TimeUnit.SECONDS.toNanos(5);go.countDown();
                for(var f:futures)f.get(15,TimeUnit.SECONDS);pool.shutdown();
                var m=new LinkedHashMap<>(s.report((System.nanoTime()-start)/1e9));m.put("workers",workers);m.put("successful_writes",writes.get());m.put("cas_conflicts",conflicts.get());
                long actual=bookings.byId(user(1),id(200000001)).orElseThrow().version();boolean safe=actual==initial+writes.get();m.put("version_invariant",safe);
                correctness&=safe;contention.add(m);System.out.println("CONTENTION "+JSON.writeValueAsString(m));
            }
            report.put("contention",contention);report.put("correctness_pass",correctness);
            try{report.put("allocation",jdbc.queryForList("SELECT name,sum(pgsize) AS bytes FROM dbstat GROUP BY name"));}catch(Exception unsupported){report.put("allocation","dbstat unavailable");}
            report.put("counts",jdbc.queryForList("SELECT 'requests' AS name,count(*) AS rows FROM shipment_requests UNION ALL SELECT 'quotes',count(*) FROM shipment_quotes UNION ALL SELECT 'bookings',count(*) FROM shipment_bookings UNION ALL SELECT 'tasks',count(*) FROM booking_reconciliation_tasks"));
            report.put("heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),report);
    }
}
