package dev.diogo.paperspotlights.update;

final class UpdaterException extends Exception {
    private static final long serialVersionUID = 1L;

    private final UpdateFailure failure;

    UpdaterException(UpdateFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    UpdaterException(UpdateFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    UpdateFailure failure() {
        return failure;
    }
}
