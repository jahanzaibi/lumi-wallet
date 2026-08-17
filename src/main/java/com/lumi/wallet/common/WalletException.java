package com.lumi.wallet.common;

/**
 * Every expected business failure is signalled with this exception so that it reaches the client
 * as the consistent error envelope described in HELP.md section 45.
 */
public class WalletException extends RuntimeException {

    private final ErrorCode code;

    public WalletException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public WalletException(ErrorCode code) {
        this(code, code.defaultMessage());
    }

    public static WalletException of(ErrorCode code, String format, Object... args) {
        return new WalletException(code, String.format(format, args));
    }

    public ErrorCode code() {
        return code;
    }
}
