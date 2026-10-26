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

/** Separate mutation latency from the mixed read/write percentile distribution. */
public class CasWriteProbe {
    public static void main(String[] args) throws Exception {
        Path file=Path.of(args[0]).toAbsolutePath();int size=Integer.parseInt(args[1]);
        if(!file.toString().startsWith("/tmp/")&&!file.toString().startsWith("/private/tmp/"))throw new IllegalArgumentException("Temporary test file required");
        var output=new LinkedHashMap<String,Object>();List<Object> results=new ArrayList<>();boolean safe=true;
        try(var ds=SqliteDataSources.open(file.toString(),"cas-write-probe",4,3000,"db/shipping/bootstrap.sql")) {
            var jdbc=new JdbcTemplate(ds);var bookings=new JdbcShipmentStore(jdbc);
            var quotes=new JdbcQuoteStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
            String user=DatabaseLoadRunner.user(1),id=DatabaseLoadRunner.id(200000001);
            for(int workers:new int[]{1,4,16})for(int repetition=1;repetition<=3;repetition++) {
                var writes=new DatabaseLoadRunner.Stats();var reads=new DatabaseLoadRunner.Stats();
                AtomicLong applied=new AtomicLong(),conflicts=new AtomicLong();long initial=bookings.byId(user,id).orElseThrow().version();
                var pool=Executors.newFixedThreadPool(workers);CountDownLatch ready=new CountDownLatch(workers),go=new CountDownLatch(1);long[] until={0};List<Future<?>> futures=new ArrayList<>();
                try {
                    for(int w=0;w<workers;w++){final int offset=w;futures.add(pool.submit(()->{
                        ready.countDown();try{go.await();int k=offset;while(System.nanoTime()<until[0]) {
                            boolean write=(k++%5)==0;long start=System.nanoTime();
                            try {
                                if(write){var expected=bookings.byId(user,id).orElseThrow();var value=expected.restore();value.markOutcomeUnknown();
                                    boolean changed=bookings.outcome(expected,value,System.currentTimeMillis());
                                    if(changed)applied.incrementAndGet();else conflicts.incrementAndGet();
                                    writes.add(System.nanoTime()-start,0,changed?"applied":"conflict");
                                }else {int n=ThreadLocalRandom.current().nextInt(size);quotes.request(DatabaseLoadRunner.user(n),DatabaseLoadRunner.id(n));reads.add(System.nanoTime()-start,1,"read");}
                            }catch(Exception e){(write?writes:reads).add(System.nanoTime()-start,0,e.getClass().getSimpleName());}
                        }}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    }));}
                    ready.await();long start=System.nanoTime();until[0]=start+TimeUnit.SECONDS.toNanos(5);go.countDown();for(var f:futures)f.get(15,TimeUnit.SECONDS);
                    double seconds=(System.nanoTime()-start)/1e9;boolean invariant=bookings.byId(user,id).orElseThrow().version()==initial+applied.get();
                    safe&=invariant&&applied.get()>0&&writes.outcomes.keySet().stream().allMatch(k->Set.of("applied","conflict").contains(k))&&reads.outcomes.keySet().stream().allMatch(k->k.equals("read"));
                    results.add(Map.of("workers",workers,"repetition",repetition,"writes_including_version_read_and_commit",writes.report(seconds),"reads",reads.report(seconds),"version_invariant",invariant));
                }finally{pool.shutdownNow();}
            }
        }
        output.put("results",results);output.put("correctness_pass",safe);new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),output);
        if(!safe)throw new IllegalStateException("CAS correctness or unexpected-error gate failed");
    }
}
