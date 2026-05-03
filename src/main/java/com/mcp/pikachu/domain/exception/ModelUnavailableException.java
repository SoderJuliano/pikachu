package com.mcp.pikachu.domain.exception;

public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String model, Throwable cause) {
        super("Model " + model + " is unavailable", cause);
    }
}

