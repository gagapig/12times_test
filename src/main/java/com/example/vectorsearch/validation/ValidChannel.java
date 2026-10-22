package com.example.vectorsearch.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
@Constraint(validatedBy = ChannelValidator.class)
public @interface ValidChannel {
    String message() default "渠道须为 1–64 个 UTF-16 单元，允许中文，无控制字符或首尾空白";
    boolean allowAbsent() default false;
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
