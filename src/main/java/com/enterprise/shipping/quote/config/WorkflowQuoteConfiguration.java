package com.enterprise.shipping.quote.config;

import com.enterprise.shipping.quote.application.*;
import com.enterprise.shipping.quote.port.*;
import com.enterprise.shipping.quote.infrastructure.persistence.JdbcQuoteStore;
import com.enterprise.shipping.quote.infrastructure.monitoring.MicrometerQuoteWorkflowObserver;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@Profile("workflow")
public class WorkflowQuoteConfiguration {
    @Bean QuoteWorkflowObserver quoteWorkflowObserver(MeterRegistry metrics){return new MicrometerQuoteWorkflowObserver(metrics);}
    @Bean QuoteStore quoteStore(DataSource source){return new JdbcQuoteStore(new JdbcTemplate(source),new TransactionTemplate(new DataSourceTransactionManager(source)));}
    @Bean RateComparisonPort rateComparisonPort(RateProviderPort provider){return new SingleProviderRateEngine(provider);}
    @Bean QuoteApplicationService quoteApplicationService(QuoteStore store,RateComparisonPort engine,Clock clock,
            @Value("${shipping.provider.rate-timeout}") Duration timeout,@Value("${shipping.business-timezone:America/Toronto}") String zone,QuoteWorkflowObserver observer){
        return new QuoteApplicationService(store,engine,clock,timeout,ZoneId.of(zone),observer);
    }
}
