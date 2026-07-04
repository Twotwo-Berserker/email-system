package com.mailsystem.websocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 邮件通知服务 — 通过 WebSocket 向客户端推送实时事件
 */
@Service
public class MailNotificationService {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 向指定用户推送通知
     * @param userId 目标用户ID
     * @param type   事件类型 (NEW_MAIL, MAIL_READ, MAIL_DELETED 等)
     * @param payload 事件负载
     */
    public void notifyUser(Long userId, String type, Object payload) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("payload", payload);
        message.put("timestamp", System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/user/" + userId, message);
    }

    /**
     * 新邮件通知
     */
    public void notifyNewMail(Long receiverId, Long mailId, String senderEmail, String subject) {
        Map<String, Object> data = new HashMap<>();
        data.put("mailId", mailId);
        data.put("senderEmail", senderEmail);
        data.put("subject", subject);
        notifyUser(receiverId, "NEW_MAIL", data);
    }
}
