package com.igmo.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = ValidGuessRequestValidator.class)
@Target({TYPE, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface ValidGuessRequest {

    String message() default "추측을 입력해주세요.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
