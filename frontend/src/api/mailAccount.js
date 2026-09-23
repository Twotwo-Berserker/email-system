import request from './request'

/**
 * 邮箱账户接口 —— 两条获得邮箱的途径
 *
 * <h3>一、绑定外部邮箱（需要授权码）</h3>
 * <p>
 * 用户填自己已有邮箱的地址与授权码。绑定后：用它的 SMTP 把邮件发给外部地址，
 * 用它的 IMAP 把外部来信收进站内收件箱。授权码在服务端 AES 加密存储，
 * 接口只回传"是否已配置"的布尔量，提交空值或掩码表示不修改。
 * </p>
 *
 * <h3>二、领取本域地址（无需授权码）</h3>
 * <p>
 * 用户领一个本系统域名下的地址。收信由 Cloudflare Email Routing 在边缘接收后
 * 推送进来，发信走项目级中继 —— 系统从不登录别人的邮箱，因此不存在授权码。
 * 见 {@link fetchInboundCapabilities} 与 {@link bindInboundAddress}。
 * </p>
 */

/**
 * 我的邮箱账户列表
 */
export function listMyMailAccounts() {
  return request.get('/mail-account/list')
}

/**
 * 识别邮箱服务商（只凭邮箱地址，不需要授权码）
 * <p>
 * 用于一键绑定时的实时提示：用户刚打完邮箱地址，就能看到
 * "已识别为 QQ 邮箱"以及"去这里生成授权码"。
 * </p>
 *
 * @param {string} email 邮箱地址
 * @param {boolean} useMx 是否允许 MX 记录反查（会做 DNS 查询，最长数秒）
 * @returns {Promise<{data: {
 *   recognized: boolean, source: string, providerName: string,
 *   smtpSupported: boolean, imapSupported: boolean,
 *   smtpHost: string, smtpPort: number, smtpSsl: number,
 *   imapHost: string, imapPort: number, imapSsl: number,
 *   smtpCandidates: string[], imapCandidates: string[],
 *   guideUrl: string, guideSteps: string[], warning: string, note: string,
 *   unsupportedReason: string
 * }}>}
 */
export function detectMailProvider(email, useMx = false) {
  // silent：这是"边打字边识别"，失败时不该弹出错误打断用户输入
  return request.get('/mail-account/detect', {
    params: { email, mx: useMx },
    silent: true
  })
}

/**
 * 一键绑定 —— 只需要邮箱地址与授权码
 * <p>
 * 服务器地址、端口、SSL/STARTTLS 由后端识别 + 真实连接探测得出。
 * 探测失败时 message 是多行文本，需以弹窗呈现。
 * </p>
 *
 * @param {{emailAddress: string, password: string, displayName?: string, receiveEnabled?: boolean}} data
 * @returns {Promise<{data: {account: object, smtpOk: boolean, imapOk: boolean,
 *   smtpMessage: string, imapMessage: string, notices: string[]}}>}
 */
export function quickBindMailAccount(data) {
  // silent：失败信息是多行的操作指引，交给调用方用弹窗展示
  return request.post('/mail-account/quick-bind', data, { silent: true })
}

/**
 * 绑定新邮箱（手动配置，需要自行填写服务器地址）
 * @param {object} data MailAccountRequest 结构
 */
export function createMailAccount(data) {
  return request.post('/mail-account', data)
}

/**
 * 修改邮箱配置
 * @param {number} id
 * @param {object} data 授权码留空表示不修改
 */
export function updateMailAccount(id, data) {
  return request.put(`/mail-account/${id}`, data)
}

/**
 * 解绑邮箱
 */
export function deleteMailAccount(id) {
  return request.delete(`/mail-account/${id}`)
}

/**
 * 测试 SMTP 与 IMAP 连通性
 * @returns {Promise<{data: {smtpOk: boolean, smtpError?: string, imapOk: boolean, imapError?: string}}>}
 */
export function testMailAccount(id) {
  return request.post(`/mail-account/${id}/test`)
}

/**
 * 手动触发一次收信（阻塞到本轮结束，响应里带新增邮件数）
 */
export function syncMailAccount(id) {
  return request.post(`/mail-account/${id}/sync`)
}

/**
 * 本域邮箱的能力说明 —— 能不能领地址、领了能不能发信
 *
 * <p>
 * 这两件事取决于部署方的配置（有没有绑 Cloudflare、有没有配发信中继），
 * 前端看不出来。用它决定「领取本域地址」入口显不显示、显示成什么样。
 * </p>
 *
 * @returns {Promise<{data: {
 *   inboundEnabled: boolean, domains: string[],
 *   outboundTransport: string, outboundAvailable: boolean, message: string
 * }}>}
 */
export function fetchInboundCapabilities() {
  // silent：能力说明拿不到只是"不显示这个入口"，不该弹错误打扰用户
  return request.get('/mail-account/capabilities', { silent: true })
}

/**
 * 领取一个本系统域名下的地址（无需授权码）
 *
 * @param {{address: string, displayName?: string}} data
 *   address 可以只填用户名（后端补默认域名），也可以填完整地址
 */
export function bindInboundAddress(data) {
  // silent：失败信息（地址被占用、域名不属于本实例）由调用方决定怎么展示
  return request.post('/mail-account/inbound', data, { silent: true })
}
