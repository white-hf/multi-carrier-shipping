package com.enterprise.shipping.provider.sandbox.rates.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record LineItemPayload(
        @JsonProperty("length") BigDecimal length,
        @JsonProperty("width") BigDecimal width,
        @JsonProperty("height") BigDecimal height,
        @JsonProperty("weight") BigDecimal weight) {}
