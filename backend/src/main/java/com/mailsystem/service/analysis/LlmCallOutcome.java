package com.mailsystem.service.analysis;

import com.mailsystem.entity.LlmCallLog;

/**
 * 一次 LLM HTTP 调用的原始结果。
 *
 * <h3>为什么"失败"也要有对象</h3>
 * <p>
 * 失败的信息（超时？401？解析不了？第几次尝试？）正是排查分类质量下降时
 * 最需要落库的东西。若失败只抛异常，调用方就只能记下 {@code e.getMessage()}，
 * 而 HTTP 状态码、token 消耗、实际尝试次数都会在异常的传播中丢失。
 * </p>
 * <p>
 * 因此本类<b>不表示异常</b>，它表示"调用结束了，结果是这样"，
 * 由调用方看 {@link #isSuccess()} 决定下一步。
 * </p>
 */
public class LlmCallOutcome {

    /** 上游返回的原始文本（成功时有值；失败时可能是错误响应体） */
    private String text;

    /** 见 {@link LlmCallLog} 的 STATUS_* 常量 */
    private String status;

    private Integer httpStatus;
    private Integer latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    /** 实际是第几次尝试（结构化输出容错会重试） */
    private int attempt = 1;

    private String errorCode;
    private String errorMessage;

    /** 本次调用用的 provider，供落库 */
    private String provider;

    public static LlmCallOutcome failure(String status, String errorCode, String errorMessage) {
        LlmCallOutcome outcome = new LlmCallOutcome();
        outcome.status = status;
        outcome.errorCode = errorCode;
        outcome.errorMessage = errorMessage;
        return outcome;
    }

    public boolean isSuccess() {
        return LlmCallLog.STATUS_SUCCESS.equals(status) && text != null && !text.trim().isEmpty();
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
}
