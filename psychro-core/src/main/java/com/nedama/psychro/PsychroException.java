package com.nedama.psychro;

/**
 * Thrown when an input is outside the valid range of the psychrometric equations, or when
 * the inputs describe a state that cannot exist (for example a dew point above the dry bulb).
 */
public class PsychroException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public PsychroException(String message) {
        super(message);
    }
}
