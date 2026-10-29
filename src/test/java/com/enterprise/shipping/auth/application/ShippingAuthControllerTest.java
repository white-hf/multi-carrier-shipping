package com.enterprise.shipping.auth.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.shipping.auth.api.ShippingAuthController;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;

class ShippingAuthControllerTest {
    @Test
    void lockedLoginReturnsRetryAfterAndSkipsCredentialVerification() throws Exception {
        var clock = new AuthTrafficGuardTest.MutableClock();
        var properties = AuthTrafficGuardTest.properties(4096);
        var accounts = mock(AccountAuthenticator.class);
        var controller =
                new ShippingAuthController(
                        new SessionTokenService(
                                properties.tokenSecret(), properties.tokenTtl(), clock),
                        accounts,
                        new AuthTrafficGuard(properties, clock),
                        List.of("Carrier"));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (int i = 0; i < 5; i++) {
            mvc.perform(
                            post("/api/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"username\":\"alpha\",\"password\":\"wrong-password\",\"fingerprint\":\"fingerprint-test-123\"}"))
                    .andExpect(status().isUnauthorized());
            clock.advance(Duration.ofSeconds(1));
        }
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"alpha\",\"password\":\"wrong-password\",\"fingerprint\":\"fingerprint-test-123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "899"));
        verify(accounts, times(5)).authenticate("alpha", "wrong-password");

        // The target account still accepts the correct password from an unrelated client.
        when(accounts.authenticate("alpha", "test-alpha-password"))
                .thenReturn(new AccountAuthenticator.Account("alpha", "user-alpha", "Alpha"));
        mvc.perform(
                        post("/api/auth/login")
                                .with(
                                        request -> {
                                            request.setRemoteAddr("192.0.2.42");
                                            return request;
                                        })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"alpha\",\"password\":\"test-alpha-password\",\"fingerprint\":\"fingerprint-test-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-alpha"));
        verify(accounts).authenticate("alpha", "test-alpha-password");
    }
}
