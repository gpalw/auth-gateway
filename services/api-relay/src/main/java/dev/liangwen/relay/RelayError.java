package dev.liangwen.relay;

final class RelayError extends RuntimeException {
    final int status;
    final String code;
    RelayError(int status, String code) { super(code); this.status=status; this.code=code; }
}
