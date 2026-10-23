package com.enterprise.shipping.shared.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Local deployment composition only: modules still own their repositories and transactions. */
@Configuration(proxyBeanMethods=false)
@Profile("workflow")
@EnableTransactionManagement(proxyTargetClass=true)
public class WorkflowPersistenceConfiguration {
    @Bean(destroyMethod="close") HikariDataSource workflowDataSource(@Value("${shipping.database-path:./data/workflow.db}") String path){
        var source=SqliteDataSources.open(path,"workflow",4,3000,"db/workflow/bootstrap.sql");
        try{WorkflowMigrations.migrate(source,"V001__quotes.sql","V002__bookings.sql");return source;}
        catch(RuntimeException failure){source.close();throw failure;}
    }
    @Bean PlatformTransactionManager workflowTransactionManager(DataSource source){return new DataSourceTransactionManager(source);}
}
