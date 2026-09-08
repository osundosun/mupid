package com.osundosun.mupid.api.common.gmail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Properties;

import org.springframework.stereotype.Component;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;

import jakarta.mail.Session;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class GmailProvider {
    
    private final Gmail gmailClient;
    private final GmailProperties gmailProps;

    public String sendEmail(String toEmail, String subject, String htmlBody) {
        try {
            MimeMessage mimeMessage = createMimeMessage(toEmail, subject, htmlBody);
            Message message = createGmailMessage(mimeMessage);
            Message executedMessage = gmailClient.users().messages().send("me", message).execute();
            return executedMessage.getId();
        } catch (MessagingException | IOException e) {
            log.error("Gmail 발송 실패: {}", e.getMessage(), e);
            throw new GmailException(e);
        }
    }

    private MimeMessage createMimeMessage(String toEmail, String subject, String htmlBody) throws AddressException, MessagingException, UnsupportedEncodingException {
        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props);

        MimeMessage email = new MimeMessage(session);
        email.setFrom(new InternetAddress(gmailProps.sendAddress(), "뮤피드", "UTF-8"));
        email.addRecipient(RecipientType.TO, new InternetAddress(toEmail));
        email.setSubject(subject, StandardCharsets.UTF_8.name());
        email.setContent(htmlBody, "text/html; charset=UTF-8");
        email.setSentDate(new Date());
        return email;
    }

    private Message createGmailMessage(MimeMessage emailContent) throws IOException, MessagingException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        emailContent.writeTo(buffer);
        byte[] bytes = buffer.toByteArray();

        Message message = new Message();
        message.setRaw(Base64.getUrlEncoder().encodeToString(bytes));
        return message;
    }

}
