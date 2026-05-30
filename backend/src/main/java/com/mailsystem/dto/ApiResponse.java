package com.mailsystem.dto;

import lombok.Data;

/**
 * 统一API响应格式
 * <p>使用显式构造函数替代 Lombok 的 @AllArgsConstructor/@NoArgsConstructor，
 * 避免 Java 8 下泛型类与 Lombok 构造器结合时类型推断失败的问题。</p>
 */
@Data
public class ApiResponse<T> {

    /** 状态码: 200=成功, 其他=失败 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 响应数据 */
    private T data;

    /** 无参构造（Jackson 反序列化需要） */
    public ApiResponse() {
    }

    /** 全参构造（供静态工厂方法使用） */
    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ---------- 静态工厂方法 ----------

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(500, message, null);
    }
}
