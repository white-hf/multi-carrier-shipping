package com.enterprise.shipping.shared.config;

import com.enterprise.shipping.shared.identity.UserContext;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@Profile("workflow")
public class WorkflowContextConfiguration {
    @Bean Clock workflowClock(){return Clock.systemUTC();}
    @Bean UserContext workflowUser(@Value("${shipping.demo-user-id:demo-user}") String id){
        if(id.isBlank()||id.length()>100)throw new IllegalArgumentException("A valid demo user ID is required");return ()->id;
    }
}
