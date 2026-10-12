package com.crates.crates.Global.exception;

/**
 * AI 서버로 가는 요청이 밀려 받지 않았거나 정해진 시간 안에 차례가 오지 않았다.
 *
 * <p>AI 서버가 고장 난 것이 아니라 붐비는 것이므로 502가 아니라 503으로 내린다. 다시 시도하면 대개 성공한다.</p>
 */
public class AiServerBusyException extends AiServerException {
    public AiServerBusyException(String message) {
        super(message);
    }
}
