package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 调用监控日志 —— 一次 HTTP 调用一行
 *
 * <h3>为什么每次调用都要落库</h3>
 * <p>
 * 这个项目对 LLM 的依赖是"主路径 + 规则兜底"。一旦出现"分类质量突然变差"，
 * 必须能回答三个问题：是不是在走兜底（看 {@code mail_intelligence_result.source='RULE'}
 * 的占比）？是超时还是上游报错（{@code TIMEOUT} vs {@code HTTP_ERROR}）？
 * 是谁的 Key 在失败（{@code userId}）？没有这张表，这些都只能靠翻日志猜。
 * </p>
 * <p>
 * 降级率刻意<b>不</b>在本表统计：本表只记录"调用发生了什么"，而"最终结论是谁给的"
 * 由 {@code mail_intelligence_result.source} 唯一表达。同一件事存两处必然会不一致。
 * </p>
 *
 * <h3>不存完整 URL，只存主机名</h3>
 * <p>
 * {@code api_endpoint} 可能带查询串（部分网关把凭据放在 query 里），
 * 落全量 URL 有把密钥写进数据库的风险。{@code endpoint_host} 只保留主机名。
 * </p>
 *
 * <h3>error_message 必须截断 + 脱敏</h3>
 * <p>
 * 上游的错误响应体常常回显请求头（含 {@code Authorization}）或完整请求体。
 * 写入前统一截断到列宽，并做一次密钥特征串的擦除，见
 * {@code MailAnalysisServiceImpl.sanitizeError}。
 * </p>
 */
@Data
@TableName("llm_call_log")
public class LlmCallLog {

    // ==================== 调用状态 ====================

    public static final String STATUS_SUCCESS = "SUCCESS";

    /** 连接/读取超时 */
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    /** 上游返回非 2xx */
    public static final String STATUS_HTTP_ERROR = "HTTP_ERROR";

    /** 拿到了响应但 JSON 解析失败或不合契约 */
    public static final String STATUS_PARSE_ERROR = "PARSE_ERROR";

    /** 被本地限流/熔断拒绝，未发出请求 */
    public static final String STATUS_REJECTED = "REJECTED";

    /**
     * 该用户与系统默认都没有可用 Key。
     * <p>
     * 它不是失败 —— 请求根本没发出去。管理端的成功率计算会把它从分母里扣除，
     * 否则一个大家都还没配 Key 的新部署会显示 0%，掩盖真实的调用质量。
     * </p>
     */
    public static final String STATUS_SKIPPED_NO_KEY = "SKIPPED_NO_KEY";

    /** 错误信息截断长度，与列定义一致 */
    public static final int MAX_ERROR_CHARS = 512;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一次发信的跟踪 ID，把"一封信的多位收件人"的调用串起来 */
    private String traceId;

    private Long mailId;

    /** 使用其 Key 的用户；null 表示用的是系统默认 Key */
    private Long userId;

    /** OPENAI_COMPAT / ANTHROPIC */
    private String provider;

    /** 仅主机名，不含路径与查询串 */
    private String endpointHost;

    private String modelName;

    private String status;

    private Integer httpStatus;

    private Integer latencyMs;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 第几次尝试（结构化输出容错会重试，这里记录实际是第几次成功的） */
    private Integer attempt;

    private String errorCode;

    private String errorMessage;

    private String pipelineVersion;

    private LocalDateTime createTime;
}
