package com.enterprise.shipping.provider.aggregator.rates.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddressPayload(
        @JsonProperty("attention") String attention,
        @JsonProperty("companyName") String companyName,
        @JsonProperty("streetAddress") String streetAddress,
        @JsonProperty("streetAddress2") String streetAddress2,
        @JsonProperty("city") String city,
        @JsonProperty("state") String state,
        @JsonProperty("postalCode") String postalCode,
        @JsonProperty("countryCode") String countryCode,
        @JsonProperty("phone") String phone,
        @JsonProperty("email") String email,
        @JsonProperty("residential") boolean residential) {}
