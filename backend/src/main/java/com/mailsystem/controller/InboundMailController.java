package com.mailsystem.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailsystem.config.InboundProperties;
import com.mailsystem.dto.ApiResponse;
import com.mailsystem.dto.InboundMailRequest;
import com.mailsystem.dto.InboundResultView;
import com.mailsystem.service.InboundMailService;
import com.mailsystem.util.HmacVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

/**
 * 入站邮件 Webhook — {@code POST /inbound/cloudflare}
 *
 * <h3>谁在调它</h3>
 * <p>
 * Cloudflare 的 Email Worker（见 {@code deploy/cloudflare/}）。外部邮件到达
 * 本项目域名时，Cloudflare 在边缘接收，Worker 把原始报文原样 POST 到这里。
 * <b>调用方是机器，不是用户</b>，因此这里不用 JWT，而用共享密钥的 HMAC 签名。
 * </p>
 *
 * <h3>为什么这个接口在 JwtInterceptor 里被放行</h3>
 * <p>
 * {@code WebConfig} 把 {@code /inbound/**} 排除在 JWT 拦截器之外，因为
 * Cloudflare 拿不到用户 Token。放行的前提是这里<b>自己做了等价的校验</b> ——
 * 少了 {@link HmacVerifier} 这一步，任何知道域名的人都能往任意用户
 * 收件箱里塞邮件（并被送进 LLM 分析，花掉该用户的额度）。
 * </p>
 *
 * <h3>状态码的约定</h3>
 * <ul>
 *   <li><b>200</b> —— 投递成功或重复（{@code DUPLICATE}）。Worker 视为成功</li>
 *   <li><b>200 + status=UNKNOWN_RECIPIENT/DISABLED</b> —— 不存在的收件地址。
 *       用 200 而不是 4xx 是为了让 Worker 能读到具体原因并据此<b>退信</b>；
 *       这里若直接回 4xx，Worker 只会看到"失败了"，无法区分该退信还是该重试</li>
 *   <li><b>401</b> —— 签名不通过。Worker 不该重试（重试一万次也还是错的），
 *       报错会让 Cloudflare 退信，这是对的：配置错误必须被看见</li>
 *   <li><b>503</b> —— 未配置共享密钥，功能未启用</li>
 *   <li><b>500</b> —— 暂时性故障（解析失败、数据库不可用）。
 *       Worker 抛出异常，Cloudflare 稍后重试</li>
 * </ul>
 *
 * @see com.mailsystem.util.HmacVerifier
 */
@RestController
@RequestMapping("/inbound")
public class InboundMailController {

    @Autowired
    private InboundProperties inbound;

    @Autowired
    private InboundMailService inboundMailService;

    /** 手动解析请求体：签名必须针对<b>原始字节</b>计算，见下方 receive 的说明 */
    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping("/cloudflare")
    public ResponseEntity<ApiResponse<InboundResultView>> receive(
            @RequestHeader(value = "X-Inbound-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Inbound-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {

        if (!inbound.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("本实例未启用来信接收（app.inbound.enabled=false）"));
        }
        if (!inbound.hasSharedSecret()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("未配置 INBOUND_SHARED_SECRET，无法校验来信来源"));
        }
        if (!HmacVerifier.verify(inbound.getSharedSecret(), timestamp, rawBody, signature)) {
            // 刻意不区分"签名错"和"时间戳过期"：对调用方而言处置方式一样（都不该重试），
            // 而对攻击者来说，区分开等于告诉他哪一步猜对了一半
            System.err.println("[Inbound] 签名校验失败，已拒绝（来源可能伪造，或 Worker 与本服务的密钥不一致）");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("签名校验失败"));
        }

        InboundMailRequest request;
        try {
            request = objectMapper.readValue(rawBody, InboundMailRequest.class);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("请求体不是合法的 JSON: " + e.getMessage()));
        }

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(stripWhitespace(request.getRaw()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("raw 字段不是合法的 Base64"));
        }
        if (raw.length == 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("邮件报文为空"));
        }
        if (raw.length > inbound.getMaxMessageBytes()) {
            System.err.println("[Inbound] 来信 " + raw.length + " 字节，超过上限 "
                    + inbound.getMaxMessageBytes());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(ApiResponse.error("邮件超过 " + (inbound.getMaxMessageBytes() / 1024 / 1024) + "MB 上限"));
        }

        // 到这里为止的全部校验都是"这封信配不配被处理"；
        // 下面才进入业务，抛出的异常意味着暂时性故障
        try {
            InboundResultView result = inboundMailService.receive(
                    request.getFrom(), request.getTo(), raw);
            return ResponseEntity.ok(ApiResponse.ok(result.getMessage(), result));
        } catch (Exception e) {
            System.err.println("[Inbound] 处理失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("处理失败: " + e.getMessage()));
        }
    }

    /**
     * 去掉 Base64 里的换行。
     * <p>
     * Worker 侧用 {@code btoa} 编码，理论上不带换行；但任何中转（手工 curl、
     * 日志回放）都可能引入，而标准解码器遇到换行会直接抛异常 ——
     * 这是排查起来非常费劲的一类"偶发失败"。
     * </p>
     */
    private static String stripWhitespace(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", "");
    }
}
