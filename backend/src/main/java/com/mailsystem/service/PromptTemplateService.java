package com.mailsystem.service;

import com.mailsystem.entity.PromptTemplate;

import java.util.List;

/**
 * Prompt 模板服务
 *
 * <h3>分析管线只依赖 {@link #active()} 一个方法</h3>
 * <p>
 * 其余方法都是管理端功能。把"取当前生效的 Prompt"收敛成一个方法，
 * 是为了让分析代码不需要知道版本切换的存在 —— 它拿到的是
 * {@link ActivePrompt}，里面已经包含了"正文 + 版本号"，
 * 直接调 LLM 并把版本号落进 {@code prompt_version} 即可。
 * </p>
 */
public interface PromptTemplateService {

    /**
     * 当前生效的 Prompt。
     * <p>
     * <b>永不返回 null。</b>数据库里没有可用模板时（表还没建、被全部停用、
     * 迁移未执行）返回内置兜底 Prompt —— 分析管线不该因为一段文案缺失而停摆。
     * </p>
     */
    ActivePrompt active();

    /**
     * 所有模板（管理端列表，正文以预览形式返回）
     */
    List<PromptTemplate> listAll();

    /**
     * 单个模板的完整内容（含正文全文）。
     * <p>
     * 列表接口的 {@code contentPreview} 只有 200 字符，而 Prompt 有几十行 ——
     * 管理员要判断"这一版到底写了什么、能不能启用"必须看到全文。
     * </p>
     *
     * @throws RuntimeException 模板不存在
     */
    PromptTemplate detail(Long id);

    /**
     * 新建一个版本
     *
     * @throws RuntimeException 名称/版本为空、正文为空、或同 名称+版本 已存在
     */
    PromptTemplate create(String name, String version, String content,
                          String description, boolean enabled);

    /**
     * 启用/停用某个版本。
     * <p>
     * 启用时会先把同名的全部停用，再启用目标行（两步在同一事务内），
     * 因为表结构上没有任何约束能阻止两行同时启用。
     * </p>
     *
     * @throws RuntimeException 目标不存在、或试图停用最后一个启用中的版本
     */
    PromptTemplate setEnabled(Long id, boolean enabled);

    /**
     * 删除一个版本
     *
     * @throws RuntimeException 目标不存在、或目标是启用中的版本
     */
    void delete(Long id);

    /**
     * 生效中的 Prompt：正文 + 版本号 + 是否来自内置兜底
     */
    class ActivePrompt {

        private final String content;
        private final String version;
        private final boolean builtIn;

        public ActivePrompt(String content, String version, boolean builtIn) {
            this.content = content;
            this.version = version;
            this.builtIn = builtIn;
        }

        public String getContent() {
            return content;
        }

        /**
         * 版本号，会落进 {@code mail_intelligence_result.prompt_version}。
         * <p>
         * 内置兜底时返回 {@code builtin-v1}，与数据库里的 {@code v1} 分开 ——
         * 否则运维看到"v1 的准确率下降"时分不清是库里那份还是代码兜底那份。
         * </p>
         */
        public String getVersion() {
            return version;
        }

        public boolean isBuiltIn() {
            return builtIn;
        }
    }
}
