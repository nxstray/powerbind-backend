package com.powerbind.backend.global;

// Thrown when a requested entity does not exist in the database
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
