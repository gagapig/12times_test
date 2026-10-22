package com.example.vectorsearch.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ChannelValidator implements ConstraintValidator<ValidChannel, String> {
    private boolean allowAbsent;

    @Override
    public void initialize(ValidChannel constraint) {
        allowAbsent = constraint.allowAbsent();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return allowAbsent;
        }
        if (value.isEmpty() || value.length() > 64 || !UnicodeRules.hasValidSurrogates(value)) {
            return false;
        }
        return !UnicodeRules.isSpace(value.codePointAt(0))
                && !UnicodeRules.isSpace(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
