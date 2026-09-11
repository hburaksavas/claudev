package dev.claudev.adapter.fepipeline;

/** A step failed — carries enough detail to be a readable pipeline failure message, not just a stack trace. */
public final class StepExecutionException extends Exception {

    public StepExecutionException(String message) {
        super(message);
    }

    public StepExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
