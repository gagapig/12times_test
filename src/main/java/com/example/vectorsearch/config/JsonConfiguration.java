package com.example.vectorsearch.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJsonTypes() {
        return builder -> builder.postConfigurer(mapper -> {
            for (var shape : new CoercionInputShape[] {CoercionInputShape.Integer,
                    CoercionInputShape.Float, CoercionInputShape.Boolean}) {
                mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
            }
            for (var shape : new CoercionInputShape[] {CoercionInputShape.String,
                    CoercionInputShape.EmptyString, CoercionInputShape.Float, CoercionInputShape.Boolean}) {
                mapper.coercionConfigFor(LogicalType.Integer).setCoercion(shape, CoercionAction.Fail);
            }
        });
    }
}
