package com.igmo.web.websocket.validation;

import com.igmo.domain.GuessSubmissionType;
import com.igmo.web.websocket.request.GuessRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidGuessRequestValidator implements ConstraintValidator<ValidGuessRequest, GuessRequest> {

    @Override
    public boolean isValid(GuessRequest request, ConstraintValidatorContext context) {
        if (request == null || request.submissionType() == null
                || request.submissionType() == GuessSubmissionType.DEADLINE) {
            return true;
        }
        if (request.guess() != null && !request.guess().isBlank()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("추측을 입력해주세요.")
                .addPropertyNode("guess")
                .addConstraintViolation();
        return false;
    }
}
