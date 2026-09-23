package com.mailsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM Prompt 模板（带版本号）
 *
 * <h3>为什么 Prompt 要移出代码</h3>
 * <p>
 * Prompt 措辞的改动对分类准确率的影响，往往比换模型更大。写在 Java 字符串常量里
 * 意味着每次调整都要重新编译部署，而且<b>无法知道某条历史结论是用哪版 Prompt 得出的</b>。
 * </p>
 * <p>
 * 存入本表后，分析结果会记录所用 {@code prompt_version}，
 * 反馈统计因此能按版本切片："v1 的准确率是 71%，v2 是 83%"。
 * </p>
 *
 * <h3>启用的模板必须唯一</h3>
 * <p>
 * 表上只有 {@code uk_name_version(name, version)}，<b>没有</b>约束"同 name 只有一行
 * enabled=1"。因此启用操作必须在服务层用事务保证互斥：
 * 先全量置 0，再置目标行为 1。见 {@code PromptTemplateServiceImpl.setEnabled}。
 * </p>
 * <p>
 * 若将来出现两行同时启用的意外状态，读取端按 {@code version} 倒序取第一条
 * 并打印告警，而不是静默随机选一个 —— 静默选错会让"准确率变化"无法归因。
 * </p>
 */
@Data
@TableName("prompt_template")
public class PromptTemplate {

    /** 本系统目前只用到这一个模板名 */
    public static final String NAME_MAIL_ANALYSIS = "mail_analysis";

    /** 版本号长度上限，与列定义一致 */
    public static final int MAX_VERSION_CHARS = 32;

    /** 模板名长度上限，与列定义一致 */
    public static final int MAX_NAME_CHARS = 64;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String version;

    /** 模板正文 */
    private String content;

    private String description;

    /** 是否启用: 1=启用, 0=停用 */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 正文预览（前 200 字符），供管理端列表展示。
     * <p>非数据库列：Prompt 正文可能很长，列表接口没必要全量返回。</p>
     */
    @TableField(exist = false)
    private String contentPreview;
}
