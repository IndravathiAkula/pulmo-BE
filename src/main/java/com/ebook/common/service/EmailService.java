package com.ebook.common.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EmailService {

    private static final Logger LOG = Logger.getLogger(EmailService.class);

    private final Mailer mailer;
    private final ConfigService configService;

    public EmailService(Mailer mailer, ConfigService configService) {
        this.mailer = mailer;
        this.configService = configService;
    }

    public void sendEmailVerification(String toEmail, String rawToken) {
        String frontendUrl = configService.getString("app.frontend-url", "http://localhost:3000");
        String verifyLink = frontendUrl + "/verify-email?token=" + rawToken;

        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #333;">Welcome to ebookHub!</h2>
                    <p>Thanks for registering. Please verify your email address by clicking the button below:</p>
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="%s"
                           style="background-color: #4F46E5; color: white; padding: 12px 32px;
                                  text-decoration: none; border-radius: 6px; font-size: 16px;">
                            Verify Email
                        </a>
                    </div>
                    <p style="color: #666; font-size: 14px;">
                        If the button doesn't work, copy and paste this link into your browser:<br/>
                        <a href="%s">%s</a>
                    </p>
                    <p style="color: #999; font-size: 12px;">This link expires in 24 hours.</p>
                </div>
                """.formatted(verifyLink, verifyLink, verifyLink);

        sendHtml(toEmail, "Verify your ebookHub email", html);
    }

    public void sendPasswordReset(String toEmail, String rawToken) {
        String frontendUrl = configService.getString("app.frontend-url", "http://localhost:3000");
        String resetLink = frontendUrl + "/reset-password?token=" + rawToken;

        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #333;">Password Reset Request</h2>
                    <p>We received a request to reset your password. Click the button below to set a new password:</p>
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="%s"
                           style="background-color: #DC2626; color: white; padding: 12px 32px;
                                  text-decoration: none; border-radius: 6px; font-size: 16px;">
                            Reset Password
                        </a>
                    </div>
                    <p style="color: #666; font-size: 14px;">
                        If the button doesn't work, copy and paste this link into your browser:<br/>
                        <a href="%s">%s</a>
                    </p>
                    <p style="color: #999; font-size: 12px;">This link expires in 1 hour. If you didn't request this, ignore this email.</p>
                </div>
                """.formatted(resetLink, resetLink, resetLink);

        sendHtml(toEmail, "Reset your ebookHub password", html);
    }

    public void sendAuthorCredentials(String toEmail, String rawPassword) {
        String frontendUrl = configService.getString("app.frontend-url", "http://localhost:3000");
        String loginLink = frontendUrl + "/login";

        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #333;">Your ebookHub Author Account is Ready!</h2>
                    <p>Your email has been verified. You can now log in with the credentials below:</p>
                    <div style="background-color: #F3F4F6; padding: 20px; border-radius: 8px; margin: 20px 0;">
                        <p style="margin: 5px 0;"><strong>Email:</strong> %s</p>
                        <p style="margin: 5px 0;"><strong>Temporary Password:</strong> <code style="background: #E5E7EB; padding: 2px 8px; border-radius: 4px;">%s</code></p>
                    </div>
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="%s"
                           style="background-color: #059669; color: white; padding: 12px 32px;
                                  text-decoration: none; border-radius: 6px; font-size: 16px;">
                            Login Now
                        </a>
                    </div>
                    <p style="color: #DC2626; font-size: 14px; font-weight: bold;">
                        Please change your password immediately after logging in.
                    </p>
                    <p style="color: #999; font-size: 12px;">If you did not expect this email, please ignore it.</p>
                </div>
                """.formatted(toEmail, rawPassword, loginLink);

        sendHtml(toEmail, "Your ebookHub Author Credentials", html);
    }

    public void sendBookApprovalRequest(String bookTitle, String authorEmail, String action, String bookId) {
        String adminEmail = configService.getString("app.admin-email", "taskt600@gmail.com");
        String frontendUrl = configService.getString("app.frontend-url", "http://localhost:3000");
        String reviewLink = frontendUrl + "/dashboard/admin/books/" + bookId;

        String actionColor = switch (action) {
            case "created" -> "#4F46E5";
            case "updated" -> "#D97706";
            case "deletion requested" -> "#DC2626";
            default -> "#6B7280";
        };

        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #333;">Book Approval Request</h2>
                    <p>An author has <strong style="color: %s;">%s</strong> a book that requires your review:</p>
                    <div style="background-color: #F3F4F6; padding: 20px; border-radius: 8px; margin: 20px 0;">
                        <p style="margin: 5px 0;"><strong>Book:</strong> %s</p>
                        <p style="margin: 5px 0;"><strong>Author:</strong> %s</p>
                        <p style="margin: 5px 0;"><strong>Action:</strong> %s</p>
                    </div>
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="%s"
                           style="background-color: #4F46E5; color: white; padding: 12px 32px;
                                  text-decoration: none; border-radius: 6px; font-size: 16px;">
                            Review Book
                        </a>
                    </div>
                    <p style="color: #999; font-size: 12px;">This is an automated notification from ebookHub.</p>
                </div>
                """.formatted(actionColor, action, bookTitle, authorEmail, action, reviewLink);

        sendHtml(adminEmail, "Book Approval Required: " + bookTitle, html);
    }

    private void sendHtml(String to, String subject, String html) {
        try {
            LOG.infof("Attempting SMTP send to=%s, subject=%s", to, subject);
            mailer.send(Mail.withHtml(to, subject, html));
            LOG.infof("Email successfully sent to %s", to);
        } catch (Exception e) {
            LOG.errorf(e, "SMTP FAILED — to=%s, subject=%s, error=%s", to, subject, e.getMessage());
        }
    }
}
