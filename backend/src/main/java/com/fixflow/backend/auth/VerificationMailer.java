package com.fixflow.backend.auth;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class VerificationMailer {
    private final JavaMailSender sender;
    private final AuthSettings settings;
    public VerificationMailer(JavaMailSender sender, AuthSettings settings) {
        this.sender = sender; this.settings = settings;
    }
    public void send(String email, String otp) {
        var message = new SimpleMailMessage();
        message.setFrom(settings.getMailFrom());
        message.setTo(email);
        message.setSubject("Verify your FixFlow email");
        message.setText("Your FixFlow verification code is " + otp + ". It expires in "
                + settings.getOtpTtl().toMinutes() + " minutes. If you did not request it, ignore this email.");
        sender.send(message);
    }
}
