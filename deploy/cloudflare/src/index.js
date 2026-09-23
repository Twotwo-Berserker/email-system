/**
 * Cloudflare Email Worker —— 把本域邮箱收到的信转发给邮件系统后端
 *
 * ============================================================
 * 它在整条链路里的位置
 * ============================================================
 *
 *   外部发件人 → Cloudflare MX（本项目域名）→ 本 Worker → POST /inbound/cloudflare
 *
 * 关键点：**本系统从不登录任何外部邮箱**。信是 Cloudflare 在边缘收下之后
 * 主动推给我们的，所以整条收信链路里不存在"授权码"这种东西。
 *
 * 这个 Worker 只做三件事：读出原始报文、签名、转发。它刻意不做任何解析 ——
 * 解析放在后端（{@code MimeParser}），因为那边才有完整的 JavaMail 与数据库，
 * 而且规则只写一处才不会在两边漂移。
 *
 * ============================================================
 * 部署步骤见同目录 README.md
 * ============================================================
 *
 * 环境变量：
 *   BACKEND_INBOUND_URL    后端入站接口地址，如 https://mail.example.com/api/inbound/cloudflare
 *   INBOUND_SHARED_SECRET  与后端 app.inbound.shared-secret 完全一致的共享密钥
 */

/** 与后端 HmacVerifier.SIGNATURE_PREFIX 对应，为将来换算法留出余地 */
const SIGNATURE_PREFIX = "v1=";

/** btoa 的参数长度上限（Worker 运行时的实现在这附近会抛异常） */
const BASE64_CHUNK_BYTES = 0x8000;

export default {
  /**
   * Cloudflare Email Routing 的邮件处理入口。
   *
   * @param {EmailMessage} message 收到的邮件。message.raw 是原始报文的只读流
   * @param {object} env 绑定的环境变量与密钥
   */
  async email(message, env, ctx) {
    const endpoint = env.BACKEND_INBOUND_URL;
    const secret = env.INBOUND_SHARED_SECRET;

    if (!endpoint || !secret) {
      // 配置缺失时退信，而不是把信丢掉。丢掉的话发件人以为送达了，
      // 收件人永远等不到，而且两边都查不出原因
      message.setReject("本邮件系统未完成入站配置（Worker 缺少环境变量）");
      return;
    }

    let payload;
    try {
      const raw = new Uint8Array(await new Response(message.raw).arrayBuffer());
      payload = {
        // 用信封地址而不是头里的 To：本域地址常是别名，
        // 信封地址才是"这封信实际被投到了哪个信箱"
        from: message.from,
        to: message.to,
        receivedAt: new Date().toISOString(),
        raw: toBase64(raw),
      };
    } catch (e) {
      // 读流出错多半是暂时性的，抛出去让 Cloudflare 稍后重试
      throw new Error(`读取邮件原始报文失败: ${e.message}`);
    }

    const bodyBytes = new TextEncoder().encode(JSON.stringify(payload));
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const signature = SIGNATURE_PREFIX + (await sign(secret, timestamp, bodyBytes));

    let response;
    try {
      response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Inbound-Timestamp": timestamp,
          "X-Inbound-Signature": signature,
        },
        // 直接发送已经算过签名的字节。若这里改成传字符串让 fetch 自己编码，
        // 签名与实际投递的内容就可能对不上（编码方式的差异）
        body: bodyBytes,
      });
    } catch (e) {
      // 后端不可达属于暂时性故障 —— 抛出而非退信，Cloudflare 会重试
      throw new Error(`无法连接后端 ${endpoint}: ${e.message}`);
    }

    if (response.ok) {
      const result = await readJson(response);
      const status = result && result.data && result.data.status;

      // 后端用 200 携带这两个状态码，就是为了让我们能读到原因并退信；
      // 它若直接回 4xx，这里只会看到"失败了"，无法区分该退信还是该重试
      if (status === "UNKNOWN_RECIPIENT" || status === "DISABLED") {
        message.setReject((result.data && result.data.message) || "收件地址不存在");
      }
      return;
    }

    const detail = await readText(response);

    if (response.status === 401) {
      // 密钥不一致。重试一万次也还是错的，退信才能让配置问题被看见
      message.setReject("邮件系统拒绝了本次投递（签名校验失败，请检查共享密钥配置）");
      return;
    }
    if (response.status === 400 || response.status === 413) {
      // 请求体畸形或超出大小上限，重试不会变好
      message.setReject(`邮件系统无法处理该邮件（HTTP ${response.status}）`);
      return;
    }
    if (response.status === 503) {
      // 后端未启用入站接收或未配密钥
      message.setReject("邮件系统未启用来信接收");
      return;
    }

    // 其余（500 等）按暂时性故障处理：抛出 → Cloudflare 重试
    throw new Error(`后端暂时不可用（HTTP ${response.status}）：${detail}`);
  },
};

/**
 * HMAC-SHA256(secret, timestamp + "." + body) 的小写十六进制表示。
 *
 * 必须与后端 {@code HmacVerifier.sign} 逐字节一致：
 * 算法、待签名串的拼接方式、分隔符、输出的十六进制大小写，任何一处不同
 * 都会让每一封信都被判为伪造。
 *
 * 时间戳参与签名是为了防重放 —— 只签报文体的话，抓包拿到的那一份可以被
 * 无限次重用，反复往同一个收件箱里投同一封信。
 */
async function sign(secret, timestamp, bodyBytes) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );

  // 拼成 timestamp + "." + body 的整体字节，而不是分两次 update ——
  // WebCrypto 没有流式接口，拼接是唯一能保证结果一致的做法
  const prefix = new TextEncoder().encode(`${timestamp}.`);
  const data = new Uint8Array(prefix.length + bodyBytes.length);
  data.set(prefix, 0);
  data.set(bodyBytes, prefix.length);

  const digest = await crypto.subtle.sign("HMAC", key, data);
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * 分块 Base64 编码。
 *
 * 不能直接 `btoa(String.fromCharCode(...bytes))`：展开运算是按参数个数逐个
 * 入栈的，一封带附件的信轻松上百万字节，会直接抛 "Maximum call stack size exceeded"。
 * 而且这个问题只在邮件较大时出现，测试时用的是小邮件，很容易漏过去。
 */
function toBase64(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i += BASE64_CHUNK_BYTES) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + BASE64_CHUNK_BYTES));
  }
  return btoa(binary);
}

/** 读响应体为 JSON；后端返回非 JSON（如网关错误页）时返回 null 而不是抛异常 */
async function readJson(response) {
  const text = await readText(response);
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

async function readText(response) {
  try {
    return await response.text();
  } catch (e) {
    return "";
  }
}
